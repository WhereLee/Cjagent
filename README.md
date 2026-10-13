# 智慧寄存柜 SaaS（cabinet-server）

面向城市临时寄存场景（商场 / 车站 / 景区）的多租户寄存 SaaS 后端：**存件 → 计费 → 取件 → 异常兜底**全业务闭环，按企业工程标准独立开发——26 个迭代全部通过 CI 门禁（单元 96 + 集成 218 测试、ArchUnit 架构机检）后合入主干。

> 个人项目。业务规模为设计容量（60 点位 / 180 柜机 / 4320 格口 / 日均 3 万单），压测与种子数据按此规模构造；当前仓库为纯后端，API 契约完整可用。

## 亮点速览

- **格口分配四策略可插拔**（悲观锁 / 无锁 CAS / Redisson 分柜锁 / Redis+Lua 预扣+MQ 异步落库），一条配置切换；`UNIQUE(slot_id, active_flag)` 结构性防超卖，候选判据收敛为单一 SQL 定义，杜绝多处条件漂移导致的静默多卖
- **JMeter 压测矩阵**（四种策略 × 三档并发 × 争抢场景，一条脚本复现）：1000 客户并发下单成功率 100%、P99 1.47s；1000 客户争抢单柜 10 格口**零超卖**，CAS 较悲观锁 500 并发 P99 优 27%
- **预收费资金闭环**：下单预收 4 小时（真实扣款、余额不足下单失败）、结束按实际时长多退少补且不拦人（先取件后结算）、扣不到记欠费只拦下次下单；一笔流水只动一栏的账户不变量防并发资损
- **三个 AI Agent（一套工具循环底座）**：C 端智能客服自主完成"查订单→重开柜门→转工单"（真模型 9.7s）、B 端工单简报、运维端 AI 诊断（读健康/日志/GC 产出报告）——工具集零写操作、日志按不可信输入做注入防御、领域知识以打进 jar 的 LLM wiki 组织
- **延时任务三触发源可切换**（DB 轮询 / Redis ZSet / RocketMQ 延迟消息），执行权收敛在租约条件更新；**11 种可控设备故障注入**支撑异常演练
- **多租户**（拦截器隔离 + 白名单表）、JWT 双端认证 + RBAC 五表 + 凭证作废、接口幂等（requestId + 唯一索引）、Redis Lua 固定窗口频控

## 技术栈

Java 17 · Spring Boot 3.5 · MyBatis-Plus · Flyway · Spring Security + JWT · SpringDoc ·
MySQL 8 · Redis · RocketMQ 5 · PostgreSQL（AI 扩展数据源）· Spring AI（OpenAI 兼容接入）· JMeter · GitHub Actions

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

口径：单机（4C）、Tomcat 400 线程 / 连接池 60、稳态负载（每用户一单，匹配"一人同时一单"
业务规则）；争抢场景 990 个未命中请求快速失败（cas 下被拒 P50 49ms），三策略 sold 均恰好
等于格口数。结论：CAS 在中高并发下 P99 最优，异步预扣方案与"下单即开门"产品承诺存在
结构性冲突（异步落库窗口期），保留为对照项不作为默认策略。

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

- 本仓库不含任何环境密钥与内网地址；不含前端工程（API 契约完整，界面按 `/api/mini/**`、
  `/api/admin/**` 真实路由另行实现）。
- 设备为进程内模拟通道（11 种可控故障注入），定位是**可复现的异常源**，用于验证业务容错，
  不做物理设备还原。
