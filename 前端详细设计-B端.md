# 前端详细设计 · B 端运营后台（Vue 3 + Vite + Element Plus）

> 配套：`前端开发指南.md`（范围与铁令）、`前端详细设计-C端.md`（请求层规范同构复用）。
> **优先级低于 C 端**：一期完成 C 端闭环后再做本文；本期可只做 W1/W2/W3 + L1 + O1（演示主力）。
> 冲突处理：代码/Swagger（`/v3/api-docs/1-admin`）> 本文 > 指南。

---

## 1. 工程骨架与账号准备

```
admin/
  vite.config.ts            server.proxy: /api → http://127.0.0.1:8080
  src/
    main.ts / App.vue / router/index.ts
    api/http.ts             复用 C 端 §2 规范（R<T>、code!==0 分支、401 刷新锁），加 PageResult
    stores/auth.ts          token 对 + authorities（登录后拉 /auth/me 存一次）
    views/
      Login.vue            WorkOrders.vue   WorkOrderDrawer.vue  PendingCalls.vue
      Ledger.vue           Funds.vue        Pricing.vue          OpsDiagnose.vue   Users.vue
    components/            PageTable.vue（分页/排序透传）AnomalyTag.vue PermissionGate.vue
```

依赖：`vue@3` `vue-router` `pinia` `element-plus` `axios`（或 fetch 封装）。**不加**图表库
（积压概览用表格数字就够）；**不加** mock 服务。

**首个管理员账号（仓库不预置，必须自建）**——三步，SQL 直接可跑（`cabinet_dev`）：
1. 用 `spring-security-crypto` 的 `BCrypt.hashpw("你的口令", BCrypt.gensalt(10))` 生成哈希
   （命令行 javac 跑小工具记得 `-encoding UTF-8`）。
2. 插入：`sys_user`（带哈希，`tenant_id=1`）→ `sys_user_role` 绑角色 →
   角色用迁移已建的 `online_ops(20271001)` / `field_ops(20271002)`，或自建管理员角色后绑全部权限点：
   ```sql
   -- 权限点不用抄清单，直接从字典取：
   INSERT INTO sys_role_permission (id, tenant_id, role_id, permission_id, create_time, update_time, deleted)
   SELECT 900000000 + p.id, 1, <你的角色id>, p.id, NOW(3), NOW(3), 0
     FROM sys_permission p WHERE p.deleted = 0;
   ```
3. 登录：`tenantCode=platform`（V3 迁移已建租户）+ 用户名 + 口令。
**不要**把账号写进 `db/migration`（迁移一经发布不可改，带口令的种子必然反复改）。

---

## 2. 权限渲染（B 端的主线规则）

- 登录后 `GET /api/admin/auth/me` → `{userId, username, tenantId, end, authorities: string[]}`；
  菜单与按钮按 `authorities` 过滤（`PermissionGate` 组件收权限码字符串，`includes` 判定）。
- 常用权限码：`workorder:online:list` / `workorder:field:list` / `workorder:claim` /
  `workorder:resolve` / `workorder:dispatch-field` / `ops:diagnose` /
  `locker:ledger:list` / `locker:compartment:resolve` / `locker:door:force-open` /
  `locker:freeset:rebuild` / `locker:customer:disable` / `locker:arrears:list` /
  `locker:deposit:unrefunded-list` / `pricing:rule:list` / `pricing:rule:publish` / `system:user:list`。
- **403 是设计的一部分**：用 `online_ops` 账号点"强制开柜"应当被拒——演示时这个反差是加分项，别藏。
- 两端 token 互不通用（token 带 `end`）；拿 C 端 token 调后台接口必 401，属正常。

---

## 3. 接口与页面规格

### W1 工单双队列 `/api/admin/work-orders`
| 项 | 规格 |
|---|---|
| 数据 | `GET /online?openOnly=true`、`GET /field?openOnly=true`（`PageResult<View>`） |
| 分页 | `PageQuery`：`pageNum/pageSize(≤200)/orderBy/asc`；`orderBy` 只传声明过的属性名（`createTime`、`dueAt`），**服务端有白名单**，乱传 40000 |
| 列 | `type`、`urgent`（紧急标）、`status`、`queueSide`、`siteAddress/cabinetNo/slotNo`、`title`、`detection`（检测摘要）、`customerPhone`（已脱敏）、`dueAt`（超时倒计时）、`assigneeId`、`createTime` |
| 动作 | `POST /{id}/claim`（认领，并发只有一人成功，失败 message 展示）；`POST /{id}/resolve`（body `{requestId, resultNote≤400, needOffline}`——**requestId 前端生成**，重放不能派第二张子单；`needOffline=true` 会派生线下工单并返回子单 id）；`POST /{id}/phone-note`（`{resultNote≤300}`）；`POST /{id}/ai-brief` |
| 排序默认 | 紧急在前、开单早的先办（后端已排好，UI 不二次排序） |

### W2 待打电话清单 `GET /work-orders/pending-calls`
- 返回 `NoticeService.View[]`（channel=PHONE_CALL）：`content`（要打的电话与事由）、`orderNo`、
  `workOrderId`、`createTime`。**未回填前一直在列**——列头注明"这是欠着的债"。
- 回填：`POST /work-orders/notices/{id}/call-done` `{resultNote≤300}`（空结论会被拒）。

### W3 工单详情抽屉（点 W1 行打开）
- `GET /{id}/timeline` → `EventView[] {eventType, operatorId, detail, at}`（含系统自动转化，倒序时间轴）。
- `GET /{id}/evidence` → `EvidenceView[] {moment, source, presence, confidence, aiLabel, photoRef, note, at}`
  （四个采集点：开门/关门/看管检测/代关后；`presence` 三值 UNKNOWN 要如实显示"检测不出"）。
- **AI 简报面板**：按钮 `POST /{id}/ai-brief`（限 6 次/分，耗时按 20~60s 预期，转圈时禁用按钮）→
  `Brief {summary, draftNote, similarCases[]}`；渲染三段：事件摘要 / 处理结论文本草稿 / 相似已结单。
  - **不做"保存简报"按钮**（AI 输出不落库，Jira 同款形态；`draftNote` 是草稿，业务员改后走 resolve 提交才算结论）。
  - 被追问"为什么存不下来"→ 这是设计：事实在流水与证据里，归纳随时可重新生成。

### L1 异常格口台账 `/api/admin/locker`
- `GET /compartments/anomalies?anomaly=&siteId=`（`PageResult<LedgerView>`，默认按 `anomalyAt` 升序=卡最久在前）。
  列：`cabinetNo/slotNo/siteName`、`anomaly`（原因码）、`anomalyReason`、`anomalyAt`、**`stuckMinutes`**（停留时长红黄标）、`currentOrderId`。
- 详情 `GET /compartments/{compartmentId}`（`Detail`：现状+证据+当前占用单）。
- 处置动作（逐个权限点，按 authorities 显隐）：
  - 清柜 `POST /compartments/{compartmentId}/resolve` `{note≤200 必填}`——**要求门已关**，被拒时 message 原样展示（门没关是现场事实，不是 bug）。
  - 强制开柜 `POST .../force-open` `{confirmed: true, reason≤200}`——必须弹窗二次确认勾"我已确认现场身份"，
    `confirmed=false` 或缺事由会被服务端拒。
  - 重建空闲集合 `POST /cabinets/{cabinetId}/free-set/rebuild`（预扣策略校准入口；提示"先 DEL 再 SADD，期间会少卖不会超卖"）。
  - 禁用/启用客户 `POST /customers/{customerId}/status` `{status:0|1, reason≤200 必填}`。
- `GET /compartments/anomalies/summary?siteId=` → `SummaryView{anomaly, rowsCount, longestMinutes}` 做成页顶徽标行（排班看这个）。

### F1 资金视图 `/api/admin/locker`
- 欠费清单 `GET /arrears` → `ArrearsView{customerId, arrearsPoints, arrearsOrders, lastFinishedAt}`（分→元渲染）。
- 未退押金 `GET /deposits/unrefunded` → `DepositView{depositId, orderNo, customerId, points, depositStatus, heldHours}`
  ——**历史机制遗留清单**，页面标注"押金/冻结已作废，此表仅供清尾"。

### F2 计价策略 `/api/admin/pricing`
- `GET /rules`（历史版本新→旧）· `GET /effective?siteId=`（站点优先回退全局，空=仍在吃配置默认价）。
- `POST /rules/preview?siteId=`（body 可空=按当前生效算）→ 返回各尺寸×时长的点数表，发布前必走这步。
- `POST /rules`（`Draft{siteId, effectiveAt, reason, freeMinutes, dailyCapHours, capDays, toleranceMinutes, unitSmall/Medium/Large...}`，权限 `pricing:rule:publish`）——
  `depositPoints/remoteCloseHours` 是废弃字段：输入框不做，列表里如实显示历史值。
- `POST /rules/{ruleId}/rollback` `{reason 必填}`。

### O1 运维诊断 `/api/admin/ops`（演示压轴，全读操作）
- `POST /diagnose`（**无 body**，限 3 次/分；耗时 30~60s，按钮 loading 到完成）→
  `OpsReport {healthSummary: string, logFindings: string[], crashArtifacts: string[], advice: string[]}`
  渲染四张卡片：健康摘要 / 错误日志发现 / 崩溃残留 / 处置建议。
  - 空数组显示"未见相关记录"（这是 AI 被铁律约束后的**诚实输出**，不是 bug，页面上可以标一句）。
  - 建议区顶部固定免责条："AI 诊断初判，执行前人工确认；本页只读，不对服务器做任何操作。"
- `POST /ask` `{question≤500}`（限 6 次/分）→ `{answer}` 问答气泡区。
- 失败兜底：AI 不可用时后端返回 fallback 文本（永不 5xx），直接渲染。

### U1 账号列表 `GET /api/admin/users?keyword=&status=`（`PageResult<UserView>`，手机号已脱敏）。

---

## 4. 逐页验收

- W1：online/field 两个 tab 数据不串；认领两人抢一单时第二人看到"已被他人认领"；
  resolve 勾"需跑一趟"后 field 队列出现子单（父单落 RESOLVED）。
- W2：回填后该条从清单消失（done_at 已写）。
- W3：简报重复点 6 次后第 7 次被限流（message 可见）；刷新页面后**简报内容不在**（不落库的直接证据）。
- L1：stuckMinutes 排序正确；门未关时 resolve 被拒且话术可读；force-open 不带 confirmed 被 40000 拒。
- O1：diagnose 出四段报告；停掉 Redis 再诊断，报告的 logFindings/healthSummary 应如实反映故障
  （演示"AI 读的是真数据"）。
- 权限：`field_ops` 账号看不到 `pricing:rule:publish` 按钮；越权直接调接口 403。
- 全局：与 C 端共用一个后端不串 token；列表分页 `pageSize>200` 被服务端拒。
