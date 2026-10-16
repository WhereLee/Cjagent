# 智慧寄存柜 SaaS（cabinet-server）

面向城市临时寄存场景（商场 / 车站 / 景区）的多租户寄存 SaaS 后端：**存件 → 计费 → 取件 → 异常兜底**全业务闭环，按企业工程标准独立开发——26 个迭代全部通过 CI 门禁（单元 96 + 集成 218 测试、ArchUnit 架构机检）后合入主干。

> 个人项目。仓库为纯后端，API 契约完整可用（界面由独立前端工程实现）。
> 业务规模为设计容量，种子数据由同一套生成脚本按档位放大、可复现：默认档 60 点位 / 180 柜机 / 3240 格口
> （90 台 24 门标准柜 + 90 台 12 门副柜），**压测矩阵放大到 500 点位 / 1500 柜机 / 27000 格口**，日均 3 万单为设计目标。

## 亮点速览

- **不超卖的格口分配**：四策略可插拔（悲观锁 / 无锁 CAS / Redisson 分柜锁 / Redis+Lua 预扣+MQ 异步落库），一条配置切换、业务代码零改动；`UNIQUE(slot_id, active_flag)` 结构性防超卖，候选判据收敛为单一 SQL 定义，杜绝"多处各写一遍、漏一个条件就静默多卖"
- **压测结论是拿数据换的**（JMeter 矩阵：四策略 × 三档并发 × 争抢场景，一条脚本复现、原始 .jtl 留档）：1000 客户并发下单成功率 100%、P99 1.47s；1000 客户争抢单柜 10 个格口时**三策略均恰好成交 10 笔、零超卖**；异步预扣方案在同口径下全数失败，据此判定它与"下单即开门"的产品承诺结构性冲突，只留作对照项而不做默认策略
- **资金不靠约定靠不变量**：流水只 INSERT、余额为派生值、一笔流水只动一栏，扣减一律走带条件的 UPDATE 并以影响行数判定；预收即真实扣款，结束按实际时长多退少补且不拦人（先取件后结算），扣不到记欠费、只拦下一次下单；对账作业按游标分批扫描七类差异、**只报告不改钱**，修复必须人工执行并留痕
- **异常兜底覆盖到门与物**：门态与柜内物检分开判定（门关不等于格空）、11 种可控设备故障注入支撑异常演练、遗留物走"通知物主 → 自助取回 → 复检收口"、逾期按 3/5/10 分钟阶梯升级工单并超 48h/72h 两级派单
- **四个 AI 场景共用一套工具循环底座**：C 端智能客服、B 端工单简报、运维诊断副驾（读健康/日志/GC 出报告）、遗留物识图复审——新增一个场景的成本是"1 个应用层端口 + 1 组工具 + 1 页领域 wiki"；写操作只有客服的「重开门」与「转工单」两个（限一次对话一次、只开放两种工单类型），运维与简报工具集全只读；日志按不可信输入包裹并显式作废其中指令，敏感字段不进模型上下文；stub / real 可切换，CI 与集成测试永不调用付费接口
- **分层与约定用代码守**：interfaces / application / domain / infrastructure 四层 + ArchUnit 十条规则机检（Controller 不得注入 Mapper、实体不得直出接口、AI 框架不外泄、两端接口互不依赖），破坏约定的提交直接变红
- **多租户与认证做到细节**：MyBatis-Plus 拦截器自动注入 `tenant_id`（缺上下文即 fail-fast）、登录查询显式豁免并写明安全依据；JWT 双端凭证互不通用 + RBAC 五表 + 注销黑名单；接口幂等（requestId + 唯一索引）、Redis Lua 固定窗口频控
- **延时任务三触发源可切换**（DB 轮询 / Redis ZSet / RocketMQ 延迟消息）互为备份，执行权只由任务表上的租约条件更新保证、处理器全部可重入；事务刻意分三段，以免"业务成功而状态未落"与"回滚抹掉退避计数"

## 技术栈

Java 17 · Spring Boot 3.5 · MyBatis-Plus（多租户 / 分页 / 乐观锁 / 防全表更新拦截器）· Flyway（V1–V24）·
Spring Security + JWT · SpringDoc · MySQL 8 · Redis（Lua 脚本 + Redisson）· RocketMQ 5 ·
PostgreSQL / pgvector（AI 侧独立数据源：双数据源装配与 pgvector 可用性已进 CI 集成用例）· Spring AI（OpenAI 兼容 tool-calling）·
Actuator + Micrometer / Prometheus · ArchUnit · JMeter · GitHub Actions（服务容器跑真实中间件）

## 目录结构

```
server/                          Spring Boot 工程（Maven 单模块分包）
  src/main/java/com/wherelee/cabinet/
    interfaces/                  对外接口：admin（运营后台）/ mini（用户端）/ system（自检）
    application/                 用例编排与事务边界（SlotAllocator / OrderFundService / ai 端口）
    domain/                      实体、枚举、领域规则（BaseEntity 定死审计列与租户列）
    infrastructure/              mapper、Redis、MQ、设备通道与故障注入、AI 真实现
    common/                      R / 错误码 / 异常 / traceId / 访问日志 / 幂等 / 频控
    config/                      双数据源、MyBatis-Plus、Jackson、CORS、OpenAPI
  src/main/resources/db/migration/   Flyway 版本化 DDL（V1–V24，只增不改）
  src/main/resources/ai-wiki/        AI Agent 的领域知识（运行时 schema）
  src/test/java/                     单测 + @Tag("integration") 集成测试
stress/                          格口分配压测（JMeter 计划 + 矩阵脚本 + 结果）
scripts/                         本地中间件体检 / 启动 / 种子数据 / 空闲集合同步
deploy/jvm.opts                  JVM 参数单一来源（本地脚本与 systemd 单元共用）
```

## 核心设计

### 并发与一致性

- 格口分配的候选判据收敛为一个类（`SlotCandidateQuery`：FREE + 门已关 + 无异常），三个分配器、
  预扣集合与定时校准共用同一条 SQL 定义——多处各写一遍的代价是"其中一处漏条件就静默多卖"。
- 防超卖最后一道防线在数据库：`UNIQUE KEY (slot_id, active_flag)`，同一格口同一时刻
  只允许一张在用单（`active_flag` 关闭态置 NULL，历史单无限留底不撞索引）。
- 悲观锁策略锁内仍走条件更新：**锁只负责减少冲突，正确性由条件写保证**。

### 资金

- 预收即真实扣款（`PREPAY` 流水直接减余额），结束按实际时长多退少补（`PREPAY_REFUND`），
  扣不到的部分记欠费、只拦截下一次下单——**先取件后结算，用户出口永远存在**。
- 账户不变量：流水表只 INSERT、余额为派生值、一笔流水只动一栏（points / frozen / deposit 互不污染），
  扣减一律带条件的 UPDATE，靠影响行数判定。

### AI Agent

- 三个 Agent 共享一套 Spring AI 工具循环底座：新增一个 Agent 的全部成本是
  **1 个应用层端口 + 1 个只读工具集 + 1 页领域 wiki**。
- 安全边界在代码层而非提示词层：工具集零写操作（重启/清理/删除没有 API 入口），
  日志作为不可信输入被标记包裹并显式作废其中指令，敏感字段不进模型上下文。
- stub / real 双实现：CI 与集成测试永不调用付费接口，真模型用例以环境变量门控进入回归。

## 快速启动

前置：JDK 17、MySQL 8、Redis、RocketMQ 5（本机无 Docker）。

```powershell
# 1) 起中间件并体检（以端口监听和 redis PING 为准）
powershell -ExecutionPolicy Bypass -File scripts/dev-env.ps1

# 2) 配置密钥（.env 在 .gitignore 内，绝不入库）
copy server\.env.example server\.env
# 至少填写 MYSQL_* 账号口令、JWT_SECRET（HS256 ≥ 32 字节）

# 3) 建库（只需一次；表结构由 Flyway 启动时自动创建）
mysql -u root -p -e "create database if not exists cabinet_dev character set utf8mb4;"

# 4) 启动（JVM 参数来自 deploy/jvm.opts，与 systemd 单元共用一份）
powershell -ExecutionPolicy Bypass -File scripts/run-app.ps1
curl.exe -s http://127.0.0.1:8080/api/system/health
```

接口文档：<http://127.0.0.1:8080/swagger-ui.html>（分组：`0-system` / `1-admin` / `2-mini`）

认证：后台 `POST /api/admin/auth/login`（tenantCode + username + password）；
用户端 `POST /api/mini/auth/login`（code，dev 下支持 mock 登录；首次注册需 tenantCode + phone）。
两端凭证互不通用（token 内含 end 标识）；注销靠 Redis 黑名单作废。

## 压测

```powershell
powershell -File stress/run-matrix.ps1   # 四策略 × 三档并发 × 争抢场景，一条命令复现
```

| 策略 | 100 并发 P99 | 500 并发 P99 | 1000 并发 P99 | 争抢（1000 用户抢单柜 10 格） |
|---|---|---|---|---|
| 悲观锁 | 838ms | 1648ms | 1652ms | 零超卖 |
| 无锁 CAS | 843ms | **1209ms** | **1471ms** | 零超卖 |
| Redisson | **791ms** | 1215ms | 1534ms | 零超卖 |
| Redis+Lua 预扣 + MQ 异步落库 | — | — | — | 同口径全数失败（见下方结论） |

口径：单机（4C）、Tomcat 400 线程 / 连接池 60、稳态负载（每用户一单，匹配"一人同时一单"
业务规则）；争抢场景 990 个未命中请求快速失败（cas 下被拒 P50 49ms），三策略 sold 均恰好
等于格口数。种子按 500 点位 / 1500 柜机 / 27000 格口放大，12000 个小格口远大于 1000 单，
是为了把"没位"与"抢输"分开读。

结论：CAS 在中高并发下 P99 最优；异步预扣方案在请求路径上无数据库事务，但“窗口期”与
「下单即开门」的产品承诺不相容（预扣成功而落库未到时柜机不知道该开哪扇门），保留为对照项。

## 测试与 CI

```powershell
cd server
mvn -B -ntp test                 # 单元测试，不依赖中间件
mvn -B -ntp test -Pintegration   # 集成测试，需真实中间件（连 cabinet_test 库）
```

GitHub Actions 三个 job：`unit-tests` / `secret-scan` / `integration-tests`
（服务容器 MySQL / PostgreSQL / Redis + RocketMQ），本地与 CI 跑同一批集成用例，测试代码不分叉。

## 接口契约要点

- 统一响应 `R<T>`；错误码分段：`0` 成功 / `1xxxx` 业务 / `4xxxx` 客户端 / `5xxxx` 服务端，
  HTTP 状态按错误码族映射（401xx→401 … 429xx→429，5xxxx→500）
- 雪花 ID 在 JSON 中以字符串输出（19 位超出 JS 安全整数），计数与时长保持数字
- 列表统一分页 `PageQuery`（排序字段白名单，防 `order by` 拼接注入），未指定时服务端兜底排序
- 每请求带 `X-Trace-Id` 响应头；`tenant_id` / 审计列 / 逻辑删除由基类自动填充，业务代码不手写
- 表结构只能通过 Flyway 迁移修改；密钥只存在于被 gitignore 的 `.env`

## 说明

- 本仓库不含任何环境密钥与内网地址；密钥只存在于被 gitignore 的 `.env`，`secret-scan` job 会把格式确定的密钥字串拦在仓外。
- 只收后端：登录、下单、工单、后台的接口契约与错误码已全量实现并可跑（Swagger 分组 admin / mini），界面由独立前端工程消费这些路由。
- 设备为进程内模拟通道（11 种可控故障注入），定位是**可复现的异常源**，用来验证业务容错，不做物理设备还原；接真机只需换个 `DeviceChannel` 实现。
- AI 依赖被 ArchUnit 锁在 `infrastructure.ai` 一个包内，哪天换框架或自写工具循环，业务层只看到一个端口接口。
