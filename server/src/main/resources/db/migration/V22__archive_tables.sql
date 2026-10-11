-- ============================================================================
-- V22：只增表的冷热分层（A-5）
--
-- 为什么现在建：`biz_device_report`（每次开门/关门/心跳都一行）与 `biz_delay_task`（每张单的
-- 看管、逾期、对账任务都一行且终态后不再被读）是本项目增长最快的两张表。趁库里还没有真数据
-- 把机制立起来，比以后一边在线上一边搬要安全得多——搬数据这件事永远不该在高峰期做。
--
-- 只搬这两张，不搬别的（都是想清楚的，不是"先留着"）：
--   · `biz_point_txn` 不能搬：`verifyLedger/recalculate` 要按**全部**流水重算栏位，
--     搬走一部分就等于让对账少算，那条“∑流水 = 各栏之和”的不变量当场失真；
--   · `biz_device_command` 不能搬：`countAttempts` 按 `单号:动作:` 前缀数“这是第几次”，
--     搬走旧行会让编号回卷，而编号是重试幂等键的一部分；
--   · `biz_item_evidence` / `biz_work_order_event` 不能搬：客服与业务员要能在详情里
--     看到“从开单到关闭的每一步”与证据时间线，搬走就等于把证据丢了；
--   · `biz_notice` 不搬：归档会让用户的历史站内消息凭空消失，可见性问题不是容量问题。
--   真正安全的冷表判据只有一条：**在线读路径不会再碰这批行**。这两张符合，其余不符合。
--
-- 归档表结构与原表同构（不裁列）：将来查旧证据时不希望遇到“这列当时没存”。
-- 不建外键、不建唯一约束之外的索引：归档表只按 id 与时间被读。
-- ============================================================================

CREATE TABLE IF NOT EXISTS biz_device_report_archive
(
    id          BIGINT       NOT NULL COMMENT '原行 id（保持不变，便于追溯）',
    tenant_id   BIGINT       NOT NULL,
    cabinet_id  BIGINT       NOT NULL,
    slot_id     BIGINT       NULL,
    order_id    BIGINT       NULL,
    seq         BIGINT       NOT NULL COMMENT '设备侧单调序号',
    event_type  VARCHAR(24)  NOT NULL,
    dedup_key   VARCHAR(96)  NOT NULL,
    payload     JSON         NULL,
    reported_at DATETIME(3)  NOT NULL,
    received_at DATETIME(3)  NOT NULL,
    create_time DATETIME(3)  NOT NULL COMMENT '原行入库时间（保留期按它算）',
    archived_at DATETIME(3)  NOT NULL COMMENT '搬进归档表的时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_archive_report_dedup (dedup_key),
    KEY idx_archive_report_created (create_time),
    KEY idx_archive_report_order (order_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='设备上报流水归档表：只增不改，永久保留（用户 2026-10-12 定：只搬不删）';

CREATE TABLE IF NOT EXISTS biz_delay_task_archive
(
    id              BIGINT      NOT NULL COMMENT '原行 id',
    tenant_id       BIGINT      NOT NULL,
    task_type       VARCHAR(32) NOT NULL,
    biz_key         VARCHAR(64) NOT NULL,
    fire_at         DATETIME(3) NOT NULL,
    status          VARCHAR(16) NOT NULL COMMENT '搬走时的终态：DONE / DEAD / CANCELLED',
    attempt         INT         NOT NULL,
    last_error      VARCHAR(255) NULL,
    lease_owner     VARCHAR(64) NULL,
    lease_expire_at DATETIME(3) NULL,
    create_time     DATETIME(3) NOT NULL,
    update_time     DATETIME(3) NOT NULL,
    deleted         TINYINT     NOT NULL DEFAULT 0,
    archived_at     DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_archive_task_created (create_time),
    KEY idx_archive_task_type_key (task_type, biz_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='延迟任务终态归档表';
