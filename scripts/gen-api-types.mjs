#!/usr/bin/env node
/**
 * 从后端 OpenAPI 契约生成前端 TS 类型（mini 与 admin 各一份）。
 *
 * 为什么生成而不是手抄：契约的唯一事实源是运行中后端的 /v3/api-docs，
 * 手抄的类型会在接口演进后悄悄失真；这脚本可重跑，diff 即契约变更。
 *
 * 输入：scripts/generated/api-docs-*.json（先拉取，见文件末尾 usage）
 * 输出：mini/src/api/schema-mini.ts、admin/src/api/schema-admin.ts
 *
 * 两条关键映射规则：
 * 1. integer + format:int64 → string。后端把 Long 全局序列化为字符串（雪花 ID 超出
 *    JS 安全整数），wire 上就是 "123"，类型必须如实反映；前端禁止 parseInt/Number 还原 ID。
 * 2. R* 封装类型（R<X> 展开为 RString/RCustomerLoginView 等）原样生成，请求层按
 *    { code, message, data } 解包。
 */
import fs from 'node:fs'
import path from 'node:path'

const REPO_ROOT = path.resolve(import.meta.dirname, '..')

const JOBS = [
  {
    source: 'scripts/generated/api-docs-2-mini.json',
    target: 'mini/src/api/schema-mini.ts',
    title: 'C 端用户端（/api/mini/** 与 /api/public/**）',
  },
  {
    source: 'scripts/generated/api-docs-1-admin.json',
    target: 'admin/src/api/schema-admin.ts',
    title: 'B 端运营后台（/api/admin/**）',
  },
]

/** OpenAPI 类型 → TS 类型字符串 */
function tsType(schema, indent) {
  if (!schema || typeof schema !== 'object') return 'unknown'
  if (schema.$ref) return refName(schema.$ref)
  if (schema.enum) return schema.enum.map((v) => JSON.stringify(v)).join(' | ')
  if (schema.allOf) {
    // 合并所有 allOf 分支的属性（本契约只在少数 DTO 上出现）
    const merged = { type: 'object', properties: {}, required: [] }
    for (const part of schema.allOf) {
      const resolved = part.$ref ? null : part
      if (resolved) {
        Object.assign(merged.properties, resolved.properties || {})
        merged.required.push(...(resolved.required || []))
      }
    }
    return tsType(merged, indent)
  }
  switch (schema.type) {
    case 'string':
      return 'string'
    case 'integer':
      // int64 在 wire 上是字符串（后端 Long 全局序列化为 string）
      return schema.format === 'int64' ? 'string' : 'number'
    case 'number':
      return 'number'
    case 'boolean':
      return 'boolean'
    case 'array':
      return `${tsType(schema.items, indent)}[]`
    case 'object':
    default: {
      if (schema.properties) {
        const pad = '  '.repeat(indent + 1)
        const closePad = '  '.repeat(indent)
        const required = new Set(schema.required || [])
        const lines = Object.entries(schema.properties).map(([key, prop]) => {
          const optional = required.has(key) ? '' : '?'
          return `${pad}${quoteKey(key)}${optional}: ${tsType(prop, indent + 1)}`
        })
        return lines.length ? `{\n${lines.join('\n')}\n${closePad}}` : 'Record<string, unknown>'
      }
      if (schema.additionalProperties) {
        return `Record<string, ${tsType(schema.additionalProperties, indent)}>`
      }
      return 'unknown'
    }
  }
}

function refName(ref) {
  const raw = ref.replace('#/components/schemas/', '')
  return sanitize(raw)
}

/** 响应/请求体的 media type 各控制器不统一（application/json 或通配），取第一个有 schema 的 */
function contentSchema(content) {
  if (!content) return null
  const media = content['application/json'] ?? content['*/*'] ?? Object.values(content)[0]
  return media?.schema ?? null
}

/** springdoc 对泛型类名展开成 R«List«SiteView»» 这种，«» 不是合法 TS 标识符 */
function sanitize(name) {
  return name.replace(/[«»<>]/g, '').replace(/[^A-Za-z0-9_$]/g, '_')
}

function quoteKey(key) {
  return /^[A-Za-z_$][A-Za-z0-9_$]*$/.test(key) ? key : JSON.stringify(key)
}

function generate(job) {
  const sourcePath = path.join(REPO_ROOT, job.source)
  const targetPath = path.join(REPO_ROOT, job.target)
  const doc = JSON.parse(fs.readFileSync(sourcePath, 'utf8'))

  const out = []
  out.push('// 本文件由 scripts/gen-api-types.mjs 自动生成，请勿手改。')
  out.push(`// 来源：${job.source}（后端 /v3/api-docs，${job.title}）`)
  out.push('// 重新生成：先起后端拉契约，再跑 node scripts/gen-api-types.mjs')
  out.push('//')
  out.push('// integer format:int64 一律映射为 string：后端 Long 全局序列化为字符串（雪花 ID')
  out.push('// 超出 JS 安全整数），前端禁止用 parseInt/Number 还原这些字段。')
  out.push('')

  const schemas = doc.components?.schemas || {}
  for (const [name, schema] of Object.entries(schemas)) {
    const tsName = sanitize(name)
    if (schema.type === 'object' || schema.properties || schema.allOf) {
      out.push(`export interface ${tsName} ${tsType(schema, 0)}`)
    } else if (schema.enum) {
      out.push(`export type ${tsName} = ${tsType(schema, 0)}`)
    } else {
      out.push(`export type ${tsName} = ${tsType(schema, 0)}`)
    }
    out.push('')
  }

  // 操作表：method + path → 入参/出参类型，供请求层做类型化封装
  out.push('/** 接口操作表：key 为 "method path"（method 小写），入参与出参类型来自契约 */')
  out.push('export interface OperationTypes {')
  for (const [p, ops] of Object.entries(doc.paths || {})) {
    for (const [method, op] of Object.entries(ops)) {
      const reqSchema = contentSchema(op.requestBody?.content)
      const resSchema = contentSchema(op.responses?.['200']?.content)
      const reqType = reqSchema ? tsType(reqSchema, 1) : 'null'
      const resType = resSchema ? tsType(resSchema, 1) : 'null'
      const key = `${method.toLowerCase()} ${p}`
      out.push(`  ${JSON.stringify(key)}: {`)
      out.push(`    summary: ${JSON.stringify(op.summary || '')}`)
      out.push(`    req: ${reqType}`)
      out.push(`    res: ${resType}`)
      out.push('  }')
    }
  }
  out.push('}')
  out.push('')
  out.push('export type OperationKey = keyof OperationTypes')
  out.push('')

  fs.mkdirSync(path.dirname(targetPath), { recursive: true })
  fs.writeFileSync(targetPath, out.join('\n'), 'utf8')
  const opCount = Object.values(doc.paths || {}).reduce((n, ops) => n + Object.keys(ops).length, 0)
  console.log(`generated ${path.relative(REPO_ROOT, targetPath)}  schemas=${Object.keys(schemas).length} ops=${opCount}`)
}

console.log('== gen-api-types ==')
for (const job of JOBS) {
  const sourcePath = path.join(REPO_ROOT, job.source)
  if (!fs.existsSync(sourcePath)) {
    console.error(`MISSING ${job.source} — 先拉契约：curl -s -o ${job.source} http://127.0.0.1:8080/v3/api-docs/<group>`)
    process.exit(2)
  }
  generate(job)
}
