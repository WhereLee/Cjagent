-- ============================================================================
-- V26：智能工单字段（2026-10-19）
--
-- 这一刀只加“事实与责任”的字段，不改状态机：
--   kind      区分设备异常（INCIDENT）与用户请求（REQUEST）
--   source    区分设备/USER/AI/SYSTEM
--   intent    用户说发生了什么
--   category  系统判的处置分类
--   priority  影响 × 紧急度算出的优先级
--   response_due_at / resolve_due_at  响应与解决分开计时
--   close_reason  人工结论/事实恢复/用户确认分开记
--   handoff_json  父单传给子单的结构化交接
--
-- type 改为可空：INCIDENT 仍必填，REQUEST 可以不借用异常类型。
-- 现有唯一索引仍包含 type；REQUEST 的 type 为 NULL 时 MySQL 允许多行，
-- 不会把同一格口的多个用户请求堵死。
-- ============================================================================

ALTER TABLE biz_work_order
    ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'INCIDENT' COMMENT 'INCIDENT 异常 / REQUEST 用户请求' AFTER tenant_id,
    ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'SYSTEM' COMMENT 'DEVICE/USER/AI/SYSTEM' AFTER kind,
    ADD COLUMN intent VARCHAR(32) NULL COMMENT '用户现象：DOOR_NOT_OPEN/DOOR_NOT_CLOSE/ITEM_MISSING/ITEM_LEFT/DEVICE_OFFLINE/BILLING_DISPUTE/PICKUP_CODE/OTHER' AFTER type,
    ADD COLUMN category VARCHAR(32) NULL COMMENT '处置分类，由分诊规则产出' AFTER intent,
    ADD COLUMN priority VARCHAR(8) NOT NULL DEFAULT 'P3' COMMENT 'P1/P2/P3/P4' AFTER urgent,
    ADD COLUMN impact VARCHAR(8) NULL COMMENT 'SINGLE 单格 / CABINET 整柜 / ACCOUNT 账户' AFTER priority,
    ADD COLUMN urgency VARCHAR(8) NULL COMMENT 'HIGH/NORMAL/LOW' AFTER impact,
    ADD COLUMN assignee_group VARCHAR(32) NULL COMMENT 'ONLINE/FIELD/CUSTOMER_SERVICE/FIELD_OPS' AFTER queue_side,
    ADD COLUMN response_due_at DATETIME(3) NULL COMMENT '首次响应时限' AFTER due_at,
    ADD COLUMN resolve_due_at DATETIME(3) NULL COMMENT '解决时限' AFTER response_due_at,
    ADD COLUMN first_response_at DATETIME(3) NULL COMMENT '首次响应时刻' AFTER claimed_at,
    ADD COLUMN close_reason VARCHAR(24) NULL COMMENT 'MANUAL/FACT_RECOVERED/USER_CONFIRMED/MERGED/CANCELLED' AFTER result_note,
    ADD COLUMN handoff_json JSON NULL COMMENT '结构化交接：诉求/已尝试动作/结果/用户是否在原地/建议下一步' AFTER snapshot_json,
    ADD COLUMN merged_into_id BIGINT NULL COMMENT '重复单合并到哪张主单' AFTER parent_id,
    ADD COLUMN reopened_count INT NOT NULL DEFAULT 0 COMMENT '重开次数' AFTER version,
    MODIFY COLUMN type VARCHAR(32) NULL COMMENT 'INCIDENT 的异常类型；REQUEST 可为空或填兼容值';

ALTER TABLE biz_work_order_event
    ADD COLUMN actor_type VARCHAR(16) NOT NULL DEFAULT 'OPERATOR' COMMENT 'USER/AI/OPERATOR/SYSTEM' AFTER event_type,
    ADD COLUMN trace_id VARCHAR(64) NULL COMMENT '一次对话/一次请求的追踪号' AFTER operator_id;
