# Cjagent · 换电柜 SaaS

面向外卖骑手的**换电柜 SaaS**：柜机（IoT）+ 骑手端小程序 + 运营/商户后台 + 多租户平台。
当前处于**阶段 0（底座）**，业务表与设备接入尚未开始。

| 阶段 | 内容 | 状态 |
|---|---|---|
| 0 底座 | 工程骨架、通用能力（响应/异常/链路/审计字段）、多租户、认证授权、中间件接入、CI | **进行中** |
| 1 换电业务 | 站点、柜机、电池、套餐、订单、计费、换电流程 | 未开始 |
| 2 设备接入 | MQTT 上报、远程开锁、指令下发、离线告警 | 未开始 |
| 3 Java Agent | 运维问答 / RAG（pgvector 已就绪） | 未开始 |

## 技术栈

- **后端** Java 17 · Spring Boot 3.5 · Maven（单模块分包）· MyBatis-Plus · Flyway · Spring Security + JWT · SpringDoc
- **数据** MySQL 8（业务主库）· PostgreSQL + pgvector（agent / 向量）· Redis（缓存、登录态、限流、设备在线判定）· RocketMQ 5（业务异步事件）
- **前端** uni-app + Vue 3 + TS（骑手端，可编译 H5 本地直连后端）· Vue 3 + Element Plus（运营后台）

## 目录

```
server/                 后端 Spring Boot 工程
  src/main/java/com/wherelee/cabinet/
    interfaces/         对外接口：admin（后台）/ mini（骑手端）/ system（自检）
    application/        用例编排与事务边界
    domain/             实体、枚举、领域服务（BaseEntity 定死审计列与租户列）
    infrastructure/     mapper、缓存、消息、外部 SDK
    common/             通用能力：R / 错误码 / 异常 / traceId / 访问日志
    config/             Spring 装配：双数据源、MyBatis-Plus、Jackson、CORS、OpenAPI
  src/main/resources/db/migration/   Flyway 版本化 DDL
  src/test/java/        单测 + @Tag(integration) 集成测试
admin/ mini/            前端工程（阶段 0 后期创建）
scripts/dev-env.ps1     本地中间件一键起停与体检
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

# 3) 建库（只需一次）
mysql -u root -p -e "create database if not exists cabinet_dev character set utf8mb4;"
psql -U postgres -c "create database cabinet_agent"
psql -U postgres -d cabinet_agent -c "create extension if not exists vector"
# 表结构由 Flyway 在应用启动时自动创建，不要手工执行 DDL

# 4) 跑起来
cd server
mvn -B spring-boot:run
curl.exe -s http://127.0.0.1:8080/api/system/health
```

接口文档：<http://127.0.0.1:8080/swagger-ui.html>（分组：`0-system` / `1-admin` / `2-mini`）

## 测试与 CI

```powershell
cd server
mvn -B -ntp test                 # 单测，不依赖中间件
mvn -B -ntp test -Pintegration   # 集成测试，需真实中间件 + server/.env（连 cabinet_test 库）
```

GitHub Actions（`.github/workflows/ci.yml`）三个 job：`unit-tests`、`secret-scan`、
`integration-tests`（服务容器 MySQL/pgvector/Redis + step 起 RocketMQ）。
**本地与 CI 跑同一批 `@Tag(integration)` 用例**，只是环境由"本机实例"换成"容器"，测试代码不分叉。
实测一次流水线约 2 分钟。

## 约定要点（细节见 `docs/架构约定.md`）

- 所有接口返回 `R<T>`；错误码分段：`0` 成功 / `1xxxx` 业务 / `4xxxx` 客户端 / `5xxxx` 服务端
- 业务异常 → HTTP 200 + 业务码；参数/认证/系统类异常 → HTTP 状态码与语义一致
- 主键是雪花 ID，**JSON 里以字符串输出**（19 位超出 JS 安全整数，前端会静默丢精度）
- 时间统一 `yyyy-MM-dd HH:mm:ss`
- 每请求有 `X-Trace-Id` 响应头，报障直接给 ID；接口耗时看服务端访问日志（含慢请求 warn）与 Nginx `$request_time`
- `tenant_id`、`create_time`、`update_time`、`deleted` 由基类与自动填充负责，业务代码不手写
- 表结构只能改 Flyway 迁移脚本；密钥只在 `.env`

## 免责说明

仓库内不含任何环境密钥与内网地址；服务器信息、账号口令一律放在被 gitignore 的本地文件中。
