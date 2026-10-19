-- ============================================================================
-- V32：首页余位查询索引（2026-10-20）
--
-- 首页按点位聚合“每种尺寸还剩多少格”，SQL 是：
--   where deleted = 0 and status = 'FREE' and door_open_at is null
--     and anomaly is null and size_type in (...)
-- 原索引 idx_slot_acquire(cabinet_id, size_type, status) 以柜机开头，
-- 跨柜机统计用不上；这里补一条以 tenant_id 开头、按状态/尺寸定位的索引。
-- 门态与异常仍回表判断：它们的选择性取决于现场，不为它们再造组合索引。
-- ============================================================================

ALTER TABLE biz_compartment
    ADD KEY idx_slot_available (tenant_id, status, size_type);
