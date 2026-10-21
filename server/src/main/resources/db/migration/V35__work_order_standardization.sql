-- ============================================================================
-- V35：工单标准化（等待用户 + 满意度调查）
--
-- 两件事都属于“记录事实”，不改变工单状态机与关闭规则：
--   1) sla_pause_reason：暂停 SLA 时记录原因。本轮只定义 WAITING_CUSTOMER（等待用户），
--      NULL 表示普通暂停（等配件等），保持原有语义不变；恢复仍由业务员手动操作。
--   2) biz_work_order_csat：用户点“确认已解决”后的一次性评价（1–5 星 + 选填备注）。
--      一单一次由唯一索引兜底；不参与任何状态迁移，不填也不影响流程。
--
-- 不改既有列的类型与含义；历史工单的 sla_pause_reason 保持 NULL。
-- ============================================================================

ALTER TABLE biz_work_order
    ADD COLUMN sla_pause_reason VARCHAR(32) NULL
        COMMENT '暂停原因：WAITING_CUSTOMER=等待用户；NULL=普通暂停' AFTER sla_paused_minutes;

CREATE TABLE IF NOT EXISTS biz_work_order_csat (
    id            BIGINT       NOT NULL COMMENT '雪花 ID',
    tenant_id     BIGINT       NOT NULL,
    work_order_id BIGINT       NOT NULL COMMENT '工单 ID（一单一次）',
    customer_id   BIGINT       NOT NULL COMMENT '评价人（必须是工单归属客户）',
    score         TINYINT      NOT NULL COMMENT '1–5 星',
    comment       VARCHAR(300) NULL COMMENT '选填备注',
    create_time   DATETIME(3)  NOT NULL DEFAULT NOW(3),
    update_time   DATETIME(3)  NOT NULL DEFAULT NOW(3) ON UPDATE NOW(3),
    deleted       TINYINT(1)   NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_csat_work_order (work_order_id),
    KEY idx_csat_customer (customer_id, create_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '工单满意度（确认解决后一次性评价）';
