# Cjagent · 储物柜 SaaS

面向城市临时寄存场景的**储物柜 SaaS**：柜机（虚拟设备，主要用于故障模拟）+ 用户端（H5 / 微信小程序）
+ 运营后台 + 多租户平台。项目由“换电柜”领域调整而来，原因见 `docs/储物柜业务规划.md` 开头。
当前处于**阶段 1（寄存业务）**：阶段 0 底座与两个前端已完成，业务地基（表与状态机）已落地。

| 阶段 | 内容 | 状态 |
|---|---|---|
| 0 底座 | 工程骨架、通用能力（响应/异常/链路/审计字段）、多租户、认证授权、中间件接入、CI | **已完成** |
| 1 寄存业务 | 点位、柜机、格口、寄存单、开柜指令与故障处置、点数与押金、超时与调度、运营后台 | **进行中** |
| 2 设备接入 | 指令下发与回执、心跳在线、**故障注入（模拟器作为可控故障源）** | 业务侧容错已完成（进程内通道），真实 MQTT 拓扑在第 16 刀 |
| 3 Java Agent | 运维问答 / RAG（pgvector 已就绪） | 未开始 |

## 技术栈

- **后端** Java 17 · Spring Boot 3.5 · Maven（单模块分包）· MyBatis-Plus · Flyway · Spring Security + JWT · SpringDoc
- **数据** MySQL 8（业务主库）· PostgreSQL + pgvector（agent / 向量）· Redis（缓存、登录态、限流、设备在线判定）· RocketMQ 5（业务异步事件）
- **前端** uni-app + Vue 3 + TS（用户端，可编译 H5 本地直连后端）· Vue 3 + Element Plus（运营后台）

## 目录

```
server/                 后端 Spring Boot 工程
  src/main/java/com/wherelee/cabinet/
    interfaces/         对外接口：admin（后台）/ mini（用户端）/ system（自检）
    application/        用例编排与事务边界
    domain/             实体、枚举、领域服务（BaseEntity 定死审计列与租户列）
    infrastructure/     mapper、缓存、消息、外部 SDK
    common/             通用能力：R / 错误码 / 异常 / traceId / 访问日志
    config/             Spring 装配：双数据源、MyBatis-Plus、Jackson、CORS、OpenAPI
  src/main/resources/db/migration/   Flyway 版本化 DDL
  src/test/java/        单测 + @Tag(integration) 集成测试
admin/                Vue3 + Vite + Element Plus 运营后台
mini/                 uni-app 用户端（H5 + 微信小程序，Vue3 + TS）
  src/pages/          寄存登录 / 首页 / 扫码（占位）/ 我的
  tools/gen-page-flow.mjs  页面流转图生成与静态检查
scripts/dev-env.ps1     本地中间件一键起停与体检
scripts/run-app.ps1     本地启动后端（读 deploy/jvm.opts 的 JVM 参数）
scripts/sync-free-sets.ps1  用 DB 真相重建 Redis 空闲格口集合（预扣策略的校准入口）
stress/slot-allocation.jmx  格口分配压测脚本（四种策略同一入口）
stress/run-round.ps1        跑一轮压测并按业务码出统计（纯 ASCII，避开 PS 5.1 中文坑）
deploy/jvm.opts         JVM 参数单一来源（本地脚本与 systemd 单元共用，非密钥文件）
ci/                     CI 用的 broker 配置
docs/架构约定.md         分层/命名/错误码/配置的唯一约定来源
```

## 本地启动

前置：本机需有 MySQL、PostgreSQL、Redis、RocketMQ（没有 Docker；Redis 与 RocketMQ 不会开机自启）。

```powershell
# 1) 起中间件并体检（以端口监听和 redis PING 为准，不看命令自述）
powershell -ExecutionPolicy Bypass -File scripts/dev-env.ps1
powershell -ExecutionPolicy Bypass -File scripts/dev-env.ps1 status

# 2) 配置密钥：复制模板后填自己的库账号（.env 已在 .gitignore 内，绝不入库）
copy server\.env.example server\.env
# 至少要填：MYSQL_*/PG_* 账号口令、JWT_SECRET（HS256 要求 ≥ 32 字节，短了启动直接失败）
#   生成一个：openssl rand -base64 48

# 3) 建库（只需一次）
mysql -u root -p -e "create database if not exists cabinet_dev character set utf8mb4;"
psql -U postgres -c "create database cabinet_agent"
psql -U postgres -d cabinet_agent -c "create extension if not exists vector"
# 表结构由 Flyway 在应用启动时自动创建，不要手工执行 DDL

# 4) 跑起来（JVM 参数从 deploy/jvm.opts 读，与将来 systemd 共用一份）
powershell -ExecutionPolicy Bypass -File scripts/run-app.ps1
curl.exe -s http://127.0.0.1:8080/api/system/health

# 指标（只埋不采）：dev 额外开放 metrics / prometheus
curl.exe -s http://127.0.0.1:8080/actuator/metrics
curl.exe -s http://127.0.0.1:8080/actuator/prometheus | Select-String jvm_memory_used
# prod 只暴露 health,info —— 未暴露的端点根本不存在（heapdump/env 等经典泄露点直接消失）
```

接口文档：<http://127.0.0.1:8080/swagger-ui.html>（分组：`0-system` / `1-admin` / `2-mini`）

## 认证授权

| 端 | 登录 | 说明 |
|---|---|---|
| 后台 | `POST /api/admin/auth/login`，body 带 `tenantCode` + `username` + `password` | 用户名只**租户内**唯一，所以必须带租户；口令用 BCrypt 存 |
| 寄存客户 | `POST /api/mini/auth/login`，body 带 `code`（首次注册再加 `tenantCode`） | dev 下 `cabinet.mini.mock-login=true` 可无 AppID 跑通；生产必须关掉 |
| 两端 | `refresh` / `logout` / `me` | access 30 分、refresh 7 天；**refresh 一次性**，用过即废 |

三条安全链：公开（`/api/system/**`、文档、actuator health）、`/api/admin/**`、`/api/mini/**`。
**两端凭证互不通用**（token 里带 `end`），客户 token 打后台接口直接 401。

注销、改权限靠 Redis 作废旧凭证（`cab:auth:` 前缀，db8）：JWT 本身删不掉，只能靠黑名单。
首个后台账号不预置在仓库里（不留可用凭据），由部署后手动创建。

## 通用能力怎么用

```java
// 需要登录但权限可控的接口
@PreAuthorize("hasAuthority('system:user:list')")     // 角色用 hasRole('OPS')

// 审计留痕（成功与失败都记，入参自动脱敏+截断）
@OperationLog(module = "tenant", operation = "创建租户")

// 防重复提交：下单/开锁/退款类接口必须显式业务键
@Idempotent(key = "#req.orderNo", requireKey = true, ttlSeconds = 60)

// 限流（默认按登录用户，未登录退化到 IP）
@RateLimit(limit = 60, windowSeconds = 60)

// 出参脱敏（日志/审计走 MaskUtils，两者都要）
@JsonMask(MaskType.PHONE) private String phone;
```

三个切面的顺序、以及“限流 fail-open / 幂等 fail-closed / 审计不保证强一致”的取舍，
记在 `docs/架构约定.md` §4.2，改之前先看那里。另有 `ArchitectureTest`（ArchUnit）在单测里
机检分层约定：domain 保持纯净、Controller 不得直接注入 Mapper、两端互不依赖、
common 不依赖 ORM、application 不反向依赖接口层。

## 分页与排序

列表接口统一用 `PageQuery`（`pageNum` / `pageSize≤200` / `orderBy` / `asc`）与
`PageResult`（`total` + `pages` + `records`）。`orderBy` 传**实体属性名**且必须在接口声明的
白名单内（`order by` 拼的是标识符，预编译帮不上忙）；不指定排序时服务端兜底 `id desc`，
否则翻页会漏记录。示例：

```
GET /api/admin/users?pageNum=1&pageSize=20&orderBy=createTime&asc=false
→ 需要权限码 system:user:list；手机号已脱敏；只返回本租户数据
```

## 前端（admin）

```bash
cd admin
npm install
npm run dev                       # http://127.0.0.1:8082
npm run lint && npm run typecheck && npm run build
```

端口用 **8082 而不是 8081**：8081 已被本机 RocketMQ 5 的 proxy 组件占用（抢它会得到
`EACCES: permission denied`，容易误读成权限问题）。后端 dev 的来源白名单在
`application-dev.yml` 里，**改端口要两边一起改**。

本地第一个后台账号（仓库不存可用口令，需自己建）：

1. 生成 BCrypt 哈希（强度 10，与后端编码器一致），用 `spring-security-crypto` 的
   `BCrypt.hashpw` 即可；用命令行 `javac` 跑小工具时要加 `-encoding UTF-8`（Windows 默认 GBK）
2. 在 `cabinet_dev` 里插：权限 `system:user:list` → 角色 → `sys_role_permission`
   → `sys_user`（带哈希）→ `sys_user_role`
3. 登录：租户编码 `platform`（V3 迁移已建好）、你建的用户名与口令

账号数据**不要写进 `db/migration`**：迁移脚本一经发布不可修改，而带口令的种子数据几乎肯定要反复改动。

## 前端（mini 用户端）

```bash
cd mini
npm install
npm run dev:h5                    # http://127.0.0.1:8083
npm run lint && npm run typecheck && npm run flow
npm run build:h5 && npm run build:mp-weixin
```

- **H5 直连后端调试**，不需要小程序 AppID：登录走后端 mock 通道（`code` 直接当 openId），
  本地 code 存在 Storage 里保持稳定（否则每登录一次就多一个新客户）。编译到微信小程序时
  改用 `uni.login` 取真实 code，mock 分支由 `mini.mock-login` + `@Profile("!prod")` 两层锁住。
- 端口 **8083**（admin 8082、后端 8080、8081 被本机 RocketMQ proxy 占用），后端 dev
  的来源白名单已同时放行两个，改端口要两边一起改。
- `npm run flow` 会从 `pages.json` 与页面源码生成 `docs/页面流转.md`（Mermaid），
  并顺带检查两件事：**跳转目标未注册**（点了白屏）与**页面不可达**（死页面），不过则退出码 1。
- 扫码页在 H5 下必然失败（`uni.scanCode` 不支持 H5），那是预期分支不是 bug。

## 测试与 CI

```powershell
cd server
mvn -B -ntp test                 # 单测，不依赖中间件
mvn -B -ntp test -Pintegration   # 集成测试，需真实中间件 + server/.env（连 cabinet_test 库）
```

GitHub Actions（`.github/workflows/ci.yml`）**五个 job**：`unit-tests`、`frontend-admin`、
`frontend-mini`、`secret-scan`、`integration-tests`（服务容器 MySQL/pgvector/Redis + step 起 RocketMQ）。
**本地与 CI 跑同一批 `@Tag(integration)` 用例**，只是环境由"本机实例"换成"容器"，测试代码不分叉。
实测一次流水线约 2~3 分钟。

## 约定要点（细节见 `docs/架构约定.md`）

- 所有接口返回 `R<T>`；错误码分段：`0` 成功 / `1xxxx` 业务 / `4xxxx` 客户端 / `5xxxx` 服务端
- HTTP 状态按错误码族给：`1xxxx` → 200；`401xx/403xx/404xx/405xx/409xx/429xx` → 对应状态码；`5xxxx` → 500
- 主键是雪花 ID，**JSON 里以字符串输出**（19 位超出 JS 安全整数，前端会静默丢精度）；
  但计数与时长（分页 total、expiresIn 等）保持数字——ID 字段声明为 `Long`，计数声明为 `long`
- 时间统一 `yyyy-MM-dd HH:mm:ss`
- 每请求有 `X-Trace-Id` 响应头，报障直接给 ID；接口耗时看服务端访问日志（含慢请求 warn）与 Nginx `$request_time`
- `tenant_id`、`create_time`、`update_time`、`deleted` 由基类与自动填充负责，业务代码不手写
- 表结构只能改 Flyway 迁移脚本；密钥只在 `.env`

## 免责说明

仓库内不含任何环境密钥与内网地址；服务器信息、账号口令一律放在被 gitignore 的本地文件中。
