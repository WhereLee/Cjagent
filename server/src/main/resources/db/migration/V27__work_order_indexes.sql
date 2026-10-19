-- ============================================================================
-- V27：智能工单索引（2026-10-19）
--
-- 设计原则：
--   1. 每个业务索引都以 tenant_id 开头，避免多租户下全表扫描；
--   2. 队列查询按 kind/queue/status/priority/create_time 覆盖；
--   3. 唯一索引把 kind 纳入，让 INCIDENT 与 REQUEST 可以同格口共存；
--   4. 事件表补 tenant_id，统计不再跨租户扫。
-- ============================================================================

ALTER TABLE biz_work_order
    DROP INDEX uk_work_order_open,
    DROP INDEX idx_work_order_queue,
    DROP INDEX idx_work_order_slot,
    DROP INDEX idx_work_order_order,
    DROP INDEX idx_work_order_parent;

ALTER TABLE biz_work_order
    ADD UNIQUE KEY uk_work_order_open (tenant_id, kind, type, slot_id, queue_side, active_flag),
    ADD KEY idx_wo_queue (tenant_id, kind, queue_side, status, priority, create_time),
    ADD KEY idx_wo_assignee (tenant_id, assignee_id, status, priority, create_time),
    ADD KEY idx_wo_response_due (tenant_id, status, response_due_at),
    ADD KEY idx_wo_resolve_due (tenant_id, status, resolve_due_at),
    ADD KEY idx_wo_parent (tenant_id, parent_id),
    ADD KEY idx_wo_slot (tenant_id, slot_id, status),
    ADD KEY idx_wo_order (tenant_id, order_no),
    ADD KEY idx_wo_customer (tenant_id, customer_id, status, create_time),
    ADD KEY idx_wo_intent (tenant_id, intent, create_time),
    ADD KEY idx_wo_close (tenant_id, close_reason, closed_at);

ALTER TABLE biz_work_order_event
    DROP INDEX idx_work_order_event;

ALTER TABLE biz_work_order_event
    ADD KEY idx_wo_event_order (tenant_id, work_order_id, create_time),
    ADD KEY idx_wo_event_type (tenant_id, event_type, create_time);
