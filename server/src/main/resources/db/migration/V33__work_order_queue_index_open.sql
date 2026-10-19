-- ============================================================================
-- V33：队列索引改成查询真正使用的键（2026-10-20）
--
-- 队列 SQL 是：
--   where deleted = 0 and tenant_id = ? and queue_side = ?
--     and status in ('PENDING','CLAIMED') order by priority, create_time limit ?
--
-- V27 的 idx_wo_queue(tenant_id, kind, queue_side, status, priority, create_time)
-- 第二列是 kind，而队列从来不按 kind 过滤——索引在 tenant_id 之后断裂，只能当前缀用；
-- 10,000 行夹具下 EXPLAIN 实测退化为 uk_work_order_open 的租户级扫描 + filesort。
--
-- 过滤条件换成 active_flag = 1 不是新概念：它就是“这张单还活动着”的结构化列
-- （开单写 1、关闭置 NULL，uk_work_order_open 也依赖它），与
-- status in ('PENDING','CLAIMED') 等价。写进索引后 (tenant_id, queue_side, active_flag)
-- 是完整等值前缀，后面的 priority, create_time 直接提供队列顺序，limit 也能提前收敛。
-- ============================================================================

ALTER TABLE biz_work_order
    DROP INDEX idx_wo_queue,
    ADD KEY idx_wo_queue (tenant_id, queue_side, active_flag, priority, create_time);
