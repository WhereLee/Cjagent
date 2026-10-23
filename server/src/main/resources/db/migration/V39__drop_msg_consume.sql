-- 删掉消费幂等表。
--
-- 它服务的"MQ 业务消息消费"链在批 2 已经拆掉（OrderPersistListener 等 7 个文件），
-- 之后这张表与 ConsumeIdempotencyGuard 一起变成零调用方的死重量——批 8 判定、用户下令删除。
--
-- 判定依据与本批删 PG 完全一致：整套组件（guard + mapper + 实体 + 租户白名单 + 表）都在，
-- 唯独没有任何调用方。批 7 的 AI 作业改用扫表认领，`claim` 的条件更新已经是
-- "业务自己幂等"的教科书实现——正是 ConsumeIdempotencyGuard 类注释所要求的做法。
-- 那段"宁可重复执行，不可漏执行"的判断已经搬进 AiJobRunner 的注释里，代码可以删，结论不该消失。
--
-- 不可逆：表里若有历史消费记录会一起消失。它零调用方，也就不可能有新的写入。

DROP TABLE IF EXISTS biz_msg_consume;
