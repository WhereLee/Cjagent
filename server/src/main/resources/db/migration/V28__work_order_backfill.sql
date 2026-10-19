-- ============================================================================
-- V28：智能工单历史回填（2026-10-19）
--
-- 只回填能从旧数据直接读出来的事实：
--   urgent=1 → P1，其余 → P3
--   due_at → resolve_due_at
--   RESOLVED → MANUAL，AUTO_CLOSED → FACT_RECOVERED，CANCELLED → CANCELLED
--   operator_id 为空的历史事件 → SYSTEM
--
-- response_due_at 故意留空：旧数据没有“首次响应”的事实，不能编。
-- ============================================================================

UPDATE biz_work_order
   SET priority = CASE WHEN urgent = 1 THEN 'P1' ELSE 'P3' END;

UPDATE biz_work_order
   SET resolve_due_at = due_at
 WHERE resolve_due_at IS NULL;

UPDATE biz_work_order
   SET close_reason = 'MANUAL'
 WHERE status = 'RESOLVED' AND close_reason IS NULL;

UPDATE biz_work_order
   SET close_reason = 'FACT_RECOVERED'
 WHERE status = 'AUTO_CLOSED' AND close_reason IS NULL;

UPDATE biz_work_order
   SET close_reason = 'CANCELLED'
 WHERE status = 'CANCELLED' AND close_reason IS NULL;

UPDATE biz_work_order_event
   SET actor_type = 'SYSTEM'
 WHERE operator_id IS NULL AND actor_type = 'OPERATOR';
