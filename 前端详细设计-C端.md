# 前端详细设计 · C 端（uni-app / H5 模式）

> 配套文档：`前端开发指南.md`（范围、铁令、环境准备、验收总纲）。本文落到**实施规格**：
> 工程骨架、请求层、页面逐页字段映射、时序、数据字典、逐页验收。
> 冲突处理顺序：**代码/Swagger > 本文 > 指南**；发现三者打架就停下问用户，不自行改后端。
>
> ⚠ 本文所有接口字段都是从后端代码逐个核对出来的（2026-10-14 基线，含第 25 刀取件凭据）。
> 核对入口：`GET /v3/api-docs/2-mini`（机器可读）或 Swagger UI 的 `2-mini` 分组。

---

## 1. 工程骨架

```
mini/
  index.html                     H5 入口
  vite.config.ts                 devServer proxy：/api → http://127.0.0.1:8080
  .env.development               VITE_API_BASE=/api
  src/
    main.ts / App.vue / pages.json / manifest.json
    api/
      http.ts                    请求层（§2）
      types.ts                   全部视图 TS interface（§4.5）
      auth.ts storage.ts wallet.ts support.ts notice.ts   按域分文件，函数一接口一签
    stores/
      auth.ts                    pinia：token 对、customer、刷新锁
      order.ts                   进行中订单缓存（一人一单的"那一张"）
    pages/
      login / sites / site-detail / create / result /
      orders / order-detail / wallet / notices / support / pickup-anon
    utils/
      points.ts                  点数(分) ↔ 元 展示换算（§4.6）
      uuid.ts                    requestId 生成（§2.4）
```

**依赖**（只这几样，别加）：`vue@3`、`vue-router`（uni-app H5 内置）、`pinia`、`typescript`、
`@dcloudio/uni-app`（CLI 模板自带）。**不引入**重型 UI 库——组件自绘够用，演示层要的是链路不是好看。

**关键配置**：
- `manifest.json → h5.devServer.proxy`（或 `vite.config.ts` 的 `server.proxy`）：
  `"/api": { "target": "http://127.0.0.1:8080", "changeOrigin": true }`。
  **不要**去加然后端 CORS（指南 §3）。
- 运行：`npm run dev:h5`（默认 5173/8080 端口冲突时随意，反正走 proxy 同域）。
- 无需微信 AppID：dev 后端开了 `cabinet.mini.mock-login=true`，任意 `code` 即建/登一个客户。

**H5 模式做不了的事（提前接受，不要绕）**：
- 不能真扫码（`wx.scanCode` 是小程序 API）。**替代**：柜机列表页点选柜机，或手输柜机编号
  （后端按 `cabinetNo` 寻址，与扫码等价）。
- 不能真推送：所谓"提醒"就是站内消息页（可见即提醒，后端本来就这么设计）。
- 不能真支付：充值走后端 mock 通道，点充值即入账。

---

## 2. 请求层规格（`api/http.ts`）

### 2.1 统一响应
```ts
interface R<T> { code: number; message: string; data: T | null; traceId: string; timestamp: string }
interface PageResult<T> { total: number; pages: number; records: T[] }  // C 端目前用不到分页
```
**判成功只看 `code === 0`，不要看 HTTP 状态**：业务失败（1xxxx）也返回 HTTP 200。

### 2.2 拦截器行为（逐条实现）
| 情况 | 行为 |
|---|---|
| 有 token | 注入 `Authorization: Bearer <accessToken>` |
| `code !== 0` | toast **后端 message 原文**（不许前端另拼文案），并把 `{code, message, traceId}` 作为错误抛出供页面分支 |
| `code === 40100` 或 HTTP 401 | 尝试用 refreshToken 换新对**一次**；成功则重放原请求；失败清 storage 回登录页。**refreshToken 是一次性的**：并发 401 时只允许一个刷新在飞（auth store 里放刷新锁/共享 Promise），其余请求排队等新 token，否则第二条刷新必然拿着已作废的旧 token 失败 |
| `code === 42900` | toast 后端 message；对触发按钮设冷却（读 message 里的"一分钟后"语义即可，不必解析窗口）；**不自动重试** |
| `code === 40900`（幂等拒绝） | toast"请勿重复提交"；说明用户双击了，不是失败 |
| 网络层失败（后端挂了） | 显示可见错误 + 重试按钮；**禁止 mock 数据兜底**（指南 §6） |

### 2.3 超时
普通请求 10s；`/support/chat` 与 AI 相关 60s（真模型一轮工具循环 10~50s 是正常，不是超时）；
`/admin/ops/*` 不在 C 端。

### 2.4 requestId 规范（幂等键）
- 生成：`uuid.ts` 里 `crypto.randomUUID()`（H5 支持）。
- **一个"用户意图"一个键**：点击"存件"生成一次并暂存；弱网失败后**重试同一意图复用同一个**；
  用户放弃并重新点击算新意图 → 新键。实现建议：表单页 `onLoad` 生成，提交成功（code 0 或 40900）后作废重生成。
- 下单、充值、工单处理类必须带（后端 `requireKey=true`，漏传直接 40000）。

---

## 3. 路由表

| 页面 | path | 参数 | 守卫 |
|---|---|---|---|
| P1 登录 | `/pages/login/index` | — | 未登录首页重定向到此 |
| P2 点位列表 | `/pages/sites/index` | — | CUSTOMER |
| P3 点位详情（柜机） | `/pages/site-detail/index` | `siteCode` | CUSTOMER |
| P4 下单页 | `/pages/create/index` | `cabinetNo` | CUSTOMER |
| P5 存件结果页 | `/pages/result/index` | `orderNo`（store 传递结果对象） | CUSTOMER |
| P6 我的订单 | `/pages/orders/index` | — | CUSTOMER |
| P7 订单详情 | `/pages/order-detail/index` | `orderNo` | CUSTOMER |
| P8 钱包 | `/pages/wallet/index` | — | CUSTOMER |
| P9 消息 | `/pages/notices/index` | — | CUSTOMER |
| P10 AI 客服 | `/pages/support/index` | `orderNo?`（从 P7 带入） | CUSTOMER |
| P11 凭码取件（隐藏） | `/pages/pickup-anon/index` | — | **无**（公开页，入口见 §5.11） |

底部 tab：点位 / 订单 / 钱包 / 消息（客服入口放 P7 与全局悬浮，不做 tab——演示重点在链路）。

---

## 4. 数据字典（UI 必须按这里渲染）

### 4.1 订单状态 `status`（后端 `OrderStatus`，11 值）
| 值 | 中文展示 | UI 分组 |
|---|---|---|
| INIT | 已下单待分配 | 进行中 |
| RESERVED | 已占格，待开门 | 进行中 |
| OPENING | 开门指令已下发 | 进行中 |
| STORED | 已投件待计费 | 进行中 |
| ACTIVE | 计费中 | 进行中 |
| TEMP_OPEN | 临时开门取物中 | 进行中 |
| SETTLING | 结算中 | 进行中 |
| CLOSED | 已完成 | 终态 |
| CANCELLED | 已取消 | 终态 |
| ABNORMAL | 异常待处理 | 进行中（**不是终态**，必有出路） |
| EXPIRED | 逾期（仍可取件） | 进行中（**不是终态**） |

规则：未知值原样显示字符串，不崩不吞；`ABNORMAL`/`EXPIRED` 不得置灰成"已结束"——
逾期单还能取件、异常单必须还有出口，这是这套状态机的设计要点，UI 跟着它走。

### 4.2 尺寸 `sizeType`
`SMALL` 小（2 元/时）· `MEDIUM` 中（4 元/时）· `LARGE` 大（6 元/时）——
头 30 分钟免费、每 24h 按 10h 计、总计费 48h 封顶（这些是后端 `bill` 快照的字段，UI 只展示不计算）。

### 4.3 错误码 → UI 行为
| code | 含义 | 行为 |
|---|---|---|
| 10000 | 通用业务失败（一人一单/欠费/锁定…） | toast message 原文；**一人一单**场景引导跳 P6/P7 |
| 10409 | 该尺寸无空闲格口 | 提示换尺寸或换柜机，回 P3（不是"重试"！没货重试无用） |
| 10410 | 格口刚被占用（抢输） | 提示可重试；重试按钮**换新 requestId** |
| 10412 | 点数不足 | 引导去 P8 充值 |
| 40000 | 参数校验失败 | 开发期问题，toast 原文 |
| 40100 | 未认证/过期 | 走刷新链（§2.2） |
| 40300 | 权限不足（客户被冻结） | toast 后回登录页 |
| 40400 | 资源不存在（含"不是你的单"同一口径） | 提示并回列表 |
| 42900 | 触发频控 | toast + 按钮冷却 |
| 50000 / 50100 | 系统/依赖不可用 | 错误态 + 重试 |

### 4.4 站内消息 `kind`（`biz_notice`）
已知值：`PICKUP_VOUCHER`（存件凭证，带取件码，第 25 刀）＋ 异常提醒类（门未关/遗留物等，
**以返回为准，未知 kind 原样显示不崩**）。`channel=SYSTEM_MESSAGE` 才会出现在 `/api/mini/notices`。

### 4.5 视图 TS 类型（字段名 1:1 照抄后端，全部 ID 是 `string`）
```ts
interface CustomerLoginView { accessToken: string; refreshToken: string; tokenType: string;
  expiresIn: number; refreshExpiresIn: number; customerId: string; tenantId: string; newRegister: boolean }
interface SiteView { siteCode: string; name: string; category: string; address: string;
  cabinetCount: number; freeSmall: number; freeMedium: number; freeLarge: number }
interface CabinetView { cabinetNo: string; name: string; status: string; onlineState: string }
interface SizeFree { sizeType: string; free: number }
interface Availability { cabinetNo: string; sizes: SizeFree[] }
interface StorageOrderView { orderNo: string; cabinetNo: string; slotNo: string; allocatedSize: string;
  status: string; estimateMinutes: number | null; triedSlots: number; strategy: string; message: string | null }
interface OrderView { orderNo: string; cabinetNo: string; slotNo: string; sizeType: string; status: string;
  startedAt: string | null; expectedFinishAt: string | null; createTime: string;
  settledPoints: number; arrearsPoints: number; leftoverRetrievable: boolean }
interface BillLine { type: string; amount: number; balanceAfter: number; remark: string; at: string }
interface BillView { orderNo: string; status: string; sizeType: string; startedAt: string | null;
  finishedAt: string | null; billedMinutes: number; unitPointsPerHour: number; freeMinutes: number;
  dailyCapHours: number; capDays: number; billedHours: number; chargePoints: number;
  prepayPoints: number; settledPoints: number; arrearsPoints: number; lines: BillLine[] }
interface WalletView { points: number; frozenPoints: number; depositPoints: number;
  arrearsPoints: number; unpaidOrders: number; activeOrders: number }
interface RechargeView { outTradeNo: string; points: number; balance: number; channel: string; status: string }
interface VoucherView { orderNo: string; cabinetNo: string; slotNo: string; sizeType: string;
  status: string; voucherCode: string; locked: boolean }
interface NoticeView { id: string; channel: string; kind: string; content: string; orderNo: string | null;
  workOrderId: string | null; createTime: string; doneAt: string | null; doneNote: string | null }
interface AgentReply { reply: string; escalated: boolean; ticketNo: string | null }
interface ClosableView { cabinetNo: string; slotNo: string; sizeType: string }
```
注意 `frozenPoints/depositPoints` 是历史栏位（现行规则不用），如实显示 0 即可，不加解释文案。

### 4.6 点数换算
后端一律是"点"（1 点 = 1 分）。**计算用整数点，展示才转元**：
```ts
const toYuan = (points: number) => (points / 100).toFixed(2);   // 只在渲染层调用；禁止用浮点做加减
```

---

## 5. 页面规格（逐页：数据源 → 渲染 → 动作）

### 5.1 P1 登录
- 表单：`code`（文本，提示"演示环境任意字符串，换一个 code 就是一个新用户"）、
  `tenantCode`（默认填种子数据租户，`newRegister=true` 时必填可编辑）、`phone`（首次注册必填，11 位）。
- 调 `POST /api/mini/auth/login` → 存 token 对（storage）+ customer 信息。
- 响应 `newRegister=true` → 引导补 tenantCode/phone 重提（**不得**拿 newRegister 做权限分支）。
- 已登录直达 P2；`logout` 放"我的"入口（P8 底部）。
- 验收：错 code 场景不存在（mock 全接受）；断网时报错可见、无假数据。

### 5.2 P2 点位列表
- `GET /api/mini/storage/sites` → 卡片：name / address / cabinetCount / 三档空闲数徽标。
- 三档全 0 → 徽标置灰"已满"（后端数字已只算正常可分配格口，UI 不要再判断）。
- 点击 → P3(`siteCode`)。空态：文案"请先跑种子数据"（引导部署者，不是用户话术）。

### 5.3 P3 点位详情（柜机列表 + 代关入口）
- `GET /api/mini/storage/sites/{siteCode}/cabinets` → 柜机行：name/cabinetNo、status、onlineState。
  `status!=ENABLED` 或 `onlineState=OFFLINE` 的行标"暂停/离线"且**不可点下单**（后端也会拒，UI 先行少报错）。
- 点柜机 → 弹层 `GET /api/mini/storage/cabinets/{cabinetNo}/availability`（三档剩余数）→ "去存件"进 P4。
- **顺手代关入口**（这条链很特别，值得演示）：同页调
  `GET /api/mini/storage/cabinets/{cabinetNo}/closable-slots`——若返回非空，展示
  "有 N 个格口门没关，可以顺手帮关" → 点某格口 → 二次确认 →
  `POST /api/mini/storage/cabinets/{cabinetNo}/slots/{slotNo}/closed-by-helper` →
  **把返回的字符串（检测结果话术）原样展示**（无物则异常解除，有物则转人工——这句话就是业务结果）。

### 5.4 P4 下单页（存件）
- 入参 `cabinetNo`（来自 P3）；先拉一次 `availability` 展示三档可选数（0 的档位置灰）。
- 表单：尺寸（SMALL/MEDIUM/LARGE）＋ 预估时长**可选**（`estimateMinutes` 15–4320，默认 60；
  说明文案："预估不影响计费，按实际时长结算"——后端字段已弱化，别做成必填强引导）。
- 顶部实时提示预收金额（读 `bill` 不回来就写"预收约 X 元"由单价×4h 估算，标注"以实际为准"——
  仅展示用估算，**不得**参与任何判定）。
- 提交：`POST /api/mini/storage/orders`，body `{requestId, cabinetNo, sizeType, estimateMinutes}`。
  loading 文案："正在为你准备格口…"（这一步后端做完预备开握手+真开门，最长 2.5s×重试，正常 1~3s）。
- 错误分支：10409→"这档没空位了，换一档或换柜机"回 P3；10410→"刚被别人占了"允许重试（**换新 requestId**）；
  10000 含"未结束"→引导 P6；10412→引导 P8；开门失败后端会**当场退单退预收**并把原因写进 message，
  toast 原文即可（不要显示"成功"）。
- 成功 → P5。

### 5.5 P5 存件结果页（本刀重点：取件凭据交付）
- 数据：`StorageOrderView`（含 `message`）。
- 渲染优先级：① **格口号 `slotNo` 大号字**（用户站在柜前要的就是这个）；② `message` 整段展示——
  第 25 刀起它包含"取件码 Vxxxxxxxx——…请记下或截图"，**取件码要单独突出渲染**（大号/等宽字体/一键复制），
  不能当普通文案一行带过；③ `allocatedSize ≠ 所选尺寸` 时并排显示"已为你换成 X 格（更大）"；
  ④ `triedSlots/strategy` 折叠在"更多信息"里（观测字段，不做主 UI）。
- 底部按钮："我已放好并关上门" → `POST /{orderNo}/door?action=CLOSE_VERIFY` → 展示返回 message →
  跳 P7；"中途再开门"跳 P7（详情页有动作区）。
- 验收勾选项：结果页能复制取件码；message 为空时不显示空壳区块。

### 5.6 P6 我的订单列表
- `GET /api/mini/storage/orders?limit=20` → 行：cabinetNo/slotNo、sizeType、状态徽标（§4.1 中文）、
  `startedAt`、`settledPoints`（分→元）、`arrearsPoints>0` 显示"欠费 X 元"红标、
  `leftoverRetrievable=true` 显示"你有物品滞留，可取回"（第 21 刀闭环入口）。
- 进行中那张排最上；点击 → P7。
- 空态："还没有寄存记录" + 去 P2 按钮。

### 5.7 P7 订单详情（动作中心）
- 头部数据复用列表项 + `GET /orders/{orderNo}/bill`（账单快照：单价/免费窗口/封顶/逐笔 `lines`）。
- **取件凭据区**（第 25 刀）：进页即 `GET /orders/{orderNo}/voucher`：
  - 活单：大字取件码 + 复制 + 说明"手机没电时，凭此码 + 手机尾号在柜机屏取件"；
  - `locked=true`：显眼提示"取件码连续输错已锁定" + **重置按钮**；
  - 终态/接口报"已失效"：隐藏凭据区（不显示废码）。
- 重置按钮：二次确认弹窗（"旧码立即作废，确认重置？"）→ `POST /orders/{orderNo}/voucher/reset`
  → 新码突出显示 + toast 后端 message。
- 动作区（按状态可用性）：
  | 动作 | 接口 | 可点的状态 |
  |---|---|---|
  | 再开一次门（中途取物） | `POST /{orderNo}/door?action=OPEN_TEMP` | ACTIVE（其他状态后端拒，message 展示） |
  | 关门确认 | `POST /{orderNo}/door?action=CLOSE_VERIFY` | OPENING/TEMP_OPEN/ACTIVE 且门开着 |
  | 取件并结算 | `POST /{orderNo}/pickup`（无 body，登录态不需要码） | 非 RESERVED/终态 |
  | 上报他人遗留物 | `POST /{orderNo}/report-leftover` | OPENING/ACTIVE/TEMP_OPEN |
  | 问 AI 客服 | 跳 P10 带 `orderNo` | 任意 |
  - **取件动作的预期行为要写进 UI 说明**：点"取件"= 结算 + 自动开门（一次请求内完成）；
    门没开成也会返回成功并带 message（"已通知现场人员…"）——**照原话展示，不要显示失败**。
  - 上报遗留物确认后：本单免费退回（后端话术），UI 跳列表。
  - `ABNORMAL` 状态页：顶部提示"该单异常处理中，可继续取件；有疑问找客服"（不是终态，别置灰）。
- 无"取消订单"按钮——**后端就没有取消接口**（下单即开门，没有"下了单没开门"的停留态），
  UI 不要留这个入口。

### 5.8 P8 钱包
- `GET /api/mini/storage/wallet` → 余额（points→元）/欠费（arrearsPoints + unpaidOrders 笔数）/
  进行中单数 activeOrders。`frozenPoints/depositPoints` 显示 0 不解释。
- 充值：输入点数（整数点，单笔 ≤ 100000 点 = 1000 元；限 10 次/分）→
  `POST /api/mini/points/recharge` `{requestId, points}` → 成功展示 `RechargeView.balance`。
  超限/超频的 message 原样 toast。
- 账单核对入口：从 P7 进（逐笔 `lines` 在这里展示，type 值以返回为准原样渲染）。

### 5.9 P9 消息
- `GET /api/mini/notices`（未读在前，最多 50 条）→ 列表：kind 中文标（`PICKUP_VOUCHER`→"存件凭证"，
  未知 kind 显示原文）、content、时间。
- 点击未读 → `POST /{id}/read`；**凭证消息**（`PICKUP_VOUCHER`）可带"查看订单"跳转 P7。
- 不做推送轮询（可见即提醒，进页刷新够用）。

### 5.10 P10 AI 客服
- 从 P7 进入带 `orderNo`，独立入口可不带。
- 对话 UI：发送 `POST /api/mini/support/chat` `{orderNo?, message}`（≤500 字，限 10 次/分）。
  loading 按 **10~50 秒**预期（真模型自主调工具；dev 若 stub 配置则秒回固定话术，都是正常）。
- 响应 `AgentReply`：`reply` 气泡；`escalated=true` 时把会话切到"已建单"态并展示 `ticketNo`
  （工单号，提示"已转人工，请留意消息"）。
- 后端永不 5xx（AI 挂了也回兜底话术），前端不需要异常分支，只需展示。
- 提示文案建议："AI 可以帮你查单、重开一次柜门、解释计费；解决不了会自动转人工。"

### 5.11 P11 凭码取件（柜机屏模拟，隐藏入口）
- 用途：演示"手机没电的第二条路"。入口放 P2 底部小字"柜机屏取件（演示）"。
- 表单：`voucherCode` + `phoneTail`（恰好 4 位数字，前端做格式校验，不做业务校验）→
  `POST /api/public/storage/pickup`（**不带 Authorization**，公开接口；按 IP 限 5 次/分）。
- 失败话术后端统一（"取件码或手机尾号不正确"）——不区分哪个错了是防枚举，UI 别猜。
- 连续错满 5 次：后端返回锁定话术（含"可在订单里重置取件码"）；原样展示。
- 成功：返回 `StorageOrderView`（含 message"柜门已打开…"），渲染同 P5 结果态。

---

## 6. 关键时序（文字版，实现按这个来）

**存件（P4 → P5）**
```
用户点"存件" →(生成/复用 requestId)→ POST orders
后端：占格 → 预收扣款 → 预备开握手(可重试/换格) → 真开门等回执(≤2.5s)
  ↳ 任一步硬失败：当场退单退预收，message 说明原因（UI toast 原文，停留 P4）
成功：200 + StorageOrderView{slotNo, message 含取件码} → P5 突出格口号与码
```

**取件（P7）**
```
点"取件结算" → POST pickup →(短事务结算 + 事务外开门，失败不回滚钱)→ 200
UI：展示 message 原文（开了门=「请取走物品并关门」；没开成=「已通知现场人员」）
     → 刷新详情（状态 CLOSED / 欠费栏可能有差额）
```

**凭据自救（P7 凭据区）**
```
GET voucher → locked=true → 提示 + 重置按钮 → 二次确认 → POST reset
→ 旧码作废、错次清零、锁定解除、凭证消息改写 → 展示新码 + 复制
```

**匿名取件（P11）**
```
POST public/pickup（码+尾号）→ 成功即结算+开门（同一套链路）→ 结果页话术
错 5 次 → 锁定话术 → 引导回 App 重置（P7 凭据区）
```

**AI 客服（P10）**
```
发送 →(后端工具循环：查单/重开门/建工单，多轮调用模型)→ 10~50s → reply(+escalated,ticketNo)
```

---

## 7. 逐页验收清单（做完一页勾一页，截图存 `screenshots/mini/`）

P1：登录拿 token；换 code 即换用户；刷新后仍在 P2。
P2：显示种子点位与三档空闲数；全 0 点位有"已满"标。
P3：availability 数字正确；ENABLED/ONLINE 才可进下单；代关清单能出结果话术（有演示数据时）。
P4：下单成功；重复点击（复用 requestId）不产生第二张单；10409/10410/10412 分支话术可见。
P5：格口号醒目；取件码可复制且与 `/voucher` 接口一致；message 原文展示；换尺寸可见。
P6：进行中单在最上；欠费/滞留徽标正确（造数用后台接口或 DB）。
P7：账单三金额自洽（prepay/charge/settled 对得上）；四个动作按状态可用；**取件后 door_open 期间该格不可被再分**（数字上验证：availability 该档不减两次）。
P8：充值入账、余额整数无浮点误差；超限被拦。
P9：凭证消息出现在列表且能跳订单；标已读生效。
P10：真模型 30s 内出 reply；escalated 时工单号能对上后台工单。
P11：正确码+尾号能开柜；错 5 次锁定；锁定后走 P7 重置自救全程通。
全局：Network 无 5xx；42900 出现时按钮冷却；后端停机时错误态可见不白屏。

---

## 8. 演示数据与联调次序

1. 种子：`node scripts/seed-locker.mjs --sites 3`（3 个点位够演示，别灌 500 个）。
2. 联调顺序按链路：**登录 → 点位 → 下单 → 结果（凭据）→ 关门 → 详情动作 → 取件 → 钱包 → 消息 → 客服 → P11 凭码取件 + 重置自救**。
3. 演示脚本（3 分钟版，讲给面试官看的路径）：
   mock 登录 → 点位选柜 → 存件（讲：下单=占格+预收+开门一步完成）→ 结果页取件码（讲：凭据交付闭环）
   → 关门 → 详情页看账单 → 故意先在 P11 用错码 5 次（讲：锁定防枚举）→ 回 P7 重置解锁（讲：自助出路）
   → 取件结算 → 钱包看差额与欠费 → P10 问"计费怎么算的"（讲：AI 只读、执行权在人）。
