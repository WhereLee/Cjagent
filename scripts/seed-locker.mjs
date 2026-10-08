#!/usr/bin/env node
/**
 * 储物柜业务地基的种子数据生成器。
 *
 * 为什么用脚本而不是手写 SQL：规模是 60 点位 / 180 柜机 / 4320 格口，
 * 手写的第二件事就是错的（格口数量与型号模板对不上），而且没法重跑。
 *
 * 重跑要“收敛”而不只是“不报错”：上一版历史单的 ON DUPLICATE 只更新了 status，
 * 改了分配规则重跑时 slot_id 仍停在旧值，看起来幂等、实际不收敛（实测踩到）。
 * 故凡属生成结果的字段都在 ON DUPLICATE 里重写一遍。
 *
 * 为什么不直连数据库：这个脚本只产出 .sql 文件，**不读也不存任何凭据**。
 * 执行由 mysql 客户端完成（见末尾打印的命令），符合本仓库"SQL 走文件执行、
 * 不走管道"的编码约定（Windows 管道会把中文打成乱码）。
 *
 * 用法：
 *   node scripts/seed-locker.mjs                     # 默认 60 点位规模，不生成历史单
 *   node scripts/seed-locker.mjs --sites 3           # 小规模冒烟
 *   node scripts/seed-locker.mjs --orders 5000       # 追加 5000 条已归档口径的历史单
 *   node scripts/seed-locker.mjs --out C:\path\x.sql # 指定输出文件
 */
import fs from 'node:fs'
import path from 'node:path'
import process from 'node:process'

const REPO_ROOT = path.resolve(import.meta.dirname, '..')

function arg(name, fallback) {
  const i = process.argv.indexOf(`--${name}`)
  return i > 0 && process.argv[i + 1] ? Number(process.argv[i + 1]) : fallback
}

const SITES = arg('sites', 60)
const ORDERS = arg('orders', 0)
const OUT = process.argv.includes('--out')
  ? process.argv[process.argv.indexOf('--out') + 1]
  : path.join(REPO_ROOT, 'scripts', 'generated', 'seed-locker.sql')

const CABINETS_PER_SITE = 3
// 型号模板：6 大 / 8 中 / 10 小（用户定的柜体设计）
const MODELS = [
  { id: 90001, code: 'STD-24', name: '24 门标准柜', large: 6, medium: 8, small: 10 },
  { id: 90002, code: 'SMALL-12', name: '12 门副柜', large: 2, medium: 4, small: 6 },
]
const TENANT = 1 // platform 根租户（V3 已建）
const BASE_TS = "NOW(3)"

const q = (v) => `'${String(v).replace(/'/g, "''")}'`
const lines = []
const push = (s) => lines.push(s)

push('-- 由 scripts/seed-locker.mjs 生成，请勿手改；重跑安全（全部 ON DUPLICATE KEY UPDATE）')
push('SET NAMES utf8mb4;')
push('')
push('-- 型号模板（平台字典，无 tenant_id）')
for (const m of MODELS) {
  push(`INSERT INTO biz_cabinet_model (id, model_code, model_name, large_count, medium_count, small_count, status, create_time, update_time, deleted)
VALUES (${m.id}, ${q(m.code)}, ${q(m.name)}, ${m.large}, ${m.medium}, ${m.small}, 1, ${BASE_TS}, ${BASE_TS}, 0)
ON DUPLICATE KEY UPDATE model_name = VALUES(model_name), large_count = VALUES(large_count), medium_count = VALUES(medium_count), small_count = VALUES(small_count);`)
}
push('')

const CATEGORIES = ['MALL', 'STATION', 'SCENIC']
let siteId = 91000
let cabinetId = 92000
let slotId = 93000
let slotCount = 0

for (let s = 1; s <= SITES; s++) {
  siteId += 1
  const category = CATEGORIES[s % CATEGORIES.length]
  const code = `SITE-${String(s).padStart(3, '0')}`
  push(`INSERT INTO biz_site (id, tenant_id, site_code, name, category, address, status, create_time, update_time, deleted)
VALUES (${siteId}, ${TENANT}, ${q(code)}, ${q(`测试点位 ${s}`)}, ${q(category)}, ${q(`城市路 ${s} 号`)}, 1, ${BASE_TS}, ${BASE_TS}, 0)
ON DUPLICATE KEY UPDATE name = VALUES(name), category = VALUES(category);`)

  for (let c = 1; c <= CABINETS_PER_SITE; c++) {
    cabinetId += 1
    const model = MODELS[(s + c) % MODELS.length]
    const cabinetNo = `CAB-${String(s).padStart(3, '0')}-${c}`
    push(`INSERT INTO biz_cabinet (id, tenant_id, site_id, cabinet_no, name, model_id, cabinet_status, online_state, create_time, update_time, deleted)
VALUES (${cabinetId}, ${TENANT}, ${siteId}, ${q(cabinetNo)}, ${q(`${cabinetNo} 号柜`)}, ${model.id}, 'ENABLED', 'UNKNOWN', ${BASE_TS}, ${BASE_TS}, 0)
ON DUPLICATE KEY UPDATE name = VALUES(name), model_id = VALUES(model_id), site_id = VALUES(site_id);`)

    const groups = [['L', 'LARGE', model.large], ['M', 'MEDIUM', model.medium], ['S', 'SMALL', model.small]]
    for (const [prefix, size, count] of groups) {
      for (let i = 1; i <= count; i++) {
        slotId += 1
        slotCount += 1
        const slotNo = `${prefix}${String(i).padStart(2, '0')}`
        push(`INSERT INTO biz_compartment (id, tenant_id, cabinet_id, slot_no, size_type, status, version, create_time, update_time, deleted)
VALUES (${slotId}, ${TENANT}, ${cabinetId}, ${q(slotNo)}, ${q(size)}, 'FREE', 0, ${BASE_TS}, ${BASE_TS}, 0)
ON DUPLICATE KEY UPDATE size_type = VALUES(size_type), cabinet_id = VALUES(cabinet_id);`)
      }
    }
  }
}

if (ORDERS > 0) {
  push('')
  push(`-- 历史单：${ORDERS} 条已关闭订单（active_flag=NULL），时间分散在过去 30 天，供冷热与统计场景使用`)
  const firstCabinetSlot = 93001
  for (let i = 1; i <= ORDERS; i++) {
    const id = 950000 + i
    const orderNo = `SO-SEED-${String(i).padStart(7, '0')}`
    // 轮转分配到前 20 个格口：每口会分到多条历史单，真正演练
    // “同一格口多条 active_flag=NULL 不冲突”这一唯一索引语义（上一版除 200 永远碰不到）
    const slot = firstCabinetSlot + ((i - 1) % 20)
    const cabinet = 92001 + Math.floor(((i - 1) % 20) / 24)
    const site = 91001 + Math.floor(((i - 1) % 20) / 24 / 3)
    push(`INSERT INTO biz_storage_order (id, tenant_id, order_no, site_id, cabinet_id, slot_id, size_type, customer_id, status, active_flag, voucher_code, estimate_minutes, started_at, finished_at, expected_finish_at, temp_open_count, deposit_points, frozen_points, settled_points, arrears_points, version, create_time, update_time, deleted)
SELECT ${id}, ${TENANT}, ${q(orderNo)}, ${site}, ${cabinet}, ${slot}, 'MEDIUM', ${900000 + (i % 500)}, 'CLOSED', NULL, ${q('VSEED' + String(i).padStart(7, '0'))}, 180,
       DATE_SUB(${BASE_TS}, INTERVAL ${i % 43200} MINUTE), DATE_SUB(${BASE_TS}, INTERVAL ${i % 43200} MINUTE), DATE_SUB(${BASE_TS}, INTERVAL ${i % 43200} MINUTE),
       0, 500, 0, ${3 + (i % 9) * 2}, 0, 0, DATE_SUB(${BASE_TS}, INTERVAL ${i % 43200} MINUTE), ${BASE_TS}, 0
FROM DUAL
WHERE EXISTS (SELECT 1 FROM biz_compartment WHERE id = ${slot})
ON DUPLICATE KEY UPDATE status = VALUES(status), slot_id = VALUES(slot_id), cabinet_id = VALUES(cabinet_id), site_id = VALUES(site_id), create_time = VALUES(create_time);
`)
  }
}

push('')
push(`SELECT '${SITES}' AS sites, COUNT(*) AS cabinets FROM biz_cabinet;`)
push(`SELECT COUNT(*) AS compartments FROM biz_compartment;`)

fs.mkdirSync(path.dirname(OUT), { recursive: true })
fs.writeFileSync(OUT, lines.join('\n') + '\n', 'utf8')

const kb = (fs.statSync(OUT).size / 1024).toFixed(0)
console.log(`已生成 ${OUT}`)
console.log(`规模：点位 ${SITES}，柜机 ${SITES * CABINETS_PER_SITE}，格口 ${slotCount}，历史单 ${ORDERS}；文件 ${kb} KB`)
console.log('执行：mysql -u <用户> --default-character-set=utf8mb4 -D cabinet_dev -e "source ' + OUT.replace(/\\/g, '/') + '"')
