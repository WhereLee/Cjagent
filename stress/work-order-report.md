# 工单队列 / AI 客服压测记录（2026-10-20）

## 口径

- 本机单实例，MySQL 8 + Redis + RocketMQ 本机；服务端 dev profile。
- **关闭限流**（`--cabinet.ratelimit.enabled=false`）：否则测到的是限流器，不是工单路径。
- 这是**冒烟/回归基线**，不是容量结论：并发只有 10（队列）/ 5（AI），用于证明
  改造后接口仍能稳定响应、指标可采集；容量结论需要单独加并发与监控。
- AI 客服在 dev 下走桩实现（未配置模型密钥），所以这里测的是端点/鉴权/限流之外的
  请求链路，不包含真实模型时延。

## 结果

命令：

```powershell
powershell -File stress/run-work-order.ps1 `
  -AdminToken "<admin jwt>" -QueueThreads 10 -ChatThreads 5 -Loops 5 -Tag work-order-smoke
```

| label | count | avg_ms | p95_ms | p99_ms | max_ms | err% |
|---|---:|---:|---:|---:|---:|---:|
| wo-queue-page（线上工单队列第一页） | 50 | 29.1 | 74 | 159 | 159 | 0 |
| ai-chat（AI 客服对话） | 25 | 30.6 | 51 | 111 | 111 | 0 |

合计 75 个请求，0 错误，总吞吐约 74 req/s（单机、短时）。

## 与索引记录的关系

- 队列 SQL 的 `EXPLAIN ANALYZE` 记录见
  [work-order-explain-2026-10.md](../docs/performance/work-order-explain-2026-10.md)：
  10,000 行夹具下队列第一页 47.2ms，SLA/父子/余位查询均走索引且 < 1ms。
- 本次 JMeter 的 74ms P95 是 10 并发短时冒烟，不等于 10,000 行夹具下的单次耗时；
  两者一起看：单页查询在阈值内，并发下没有出现错误或长尾爆炸。

## 复现

1. 启动后端（dev），确认 `/api/system/health` 为 UP。
2. 用后台账号登录拿 admin token；把 mini token 逐行写入
   `stress/results/stress-users.txt`。
3. 执行上面的命令；结果写入 `stress/results/work-order-smoke-<queue>-<chat>.jtl`。
4. 查看每个 label 的 count / P95 / 错误率；若有 429，先把限流关掉再测。

## 已知边界

- `stress-users.txt` 是一次性凭证，不要提交；脚本只读取本地文件。
- AI 真实模型时延、供应商限流与 token 成本不在本记录范围内；接真实模型后需要重跑并
  单独记录模型耗时。
- 队列查询在“未关闭工单极多”的租户上仍会 filesort；阈值与后续方案见 EXPLAIN 记录。
