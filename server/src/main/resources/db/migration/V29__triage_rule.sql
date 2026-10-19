-- ============================================================================
-- V29：分诊规则（2026-10-19）
--
-- AI 只输出用户现象（intent），不再选工单类型；这张表把 intent + 事实映射成
-- category / queue / priority / SLA。规则未命中时服务层落到 CUSTOMER_SERVICE + UNSORTED，
-- 不硬派。
--
-- 规则按租户隔离；tenant_id 必须出现在唯一键和查询索引的最左列。
-- ============================================================================

CREATE TABLE IF NOT EXISTS biz_triage_rule (
    id               BIGINT       NOT NULL COMMENT '雪花 ID',
    tenant_id        BIGINT       NOT NULL,
    rule_code        VARCHAR(64)  NOT NULL COMMENT '规则代号，如 R-DOOR-NOT-OPEN',
    intent           VARCHAR(32)  NOT NULL COMMENT '用户现象',
    category         VARCHAR(32)  NOT NULL COMMENT '处置分类',
    queue_side       VARCHAR(16)  NOT NULL COMMENT 'ONLINE/FIELD/CUSTOMER_SERVICE',
    assignee_group   VARCHAR(32)  NULL COMMENT '责任组',
    priority         VARCHAR(8)   NOT NULL COMMENT 'P1-P4',
    impact           VARCHAR(8)   NOT NULL COMMENT 'SINGLE/CABINET/ACCOUNT',
    urgency          VARCHAR(8)   NOT NULL COMMENT 'HIGH/NORMAL/LOW',
    response_minutes INT          NOT NULL COMMENT '首次响应时限（分钟）',
    resolve_minutes  INT          NOT NULL COMMENT '解决时限（分钟）',
    condition_json   JSON         NULL COMMENT '附加事实条件；当前仅保留，不参与匹配',
    version          INT          NOT NULL DEFAULT 1 COMMENT '规则版本',
    enabled          TINYINT(1)   NOT NULL DEFAULT 1,
    create_time      DATETIME(3)  NOT NULL DEFAULT NOW(3),
    update_time      DATETIME(3)  NOT NULL DEFAULT NOW(3) ON UPDATE NOW(3),
    deleted          TINYINT(1)   NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_triage_rule (tenant_id, rule_code, version),
    KEY idx_triage_lookup (tenant_id, intent, enabled, version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '智能工单分诊规则';

INSERT INTO biz_triage_rule
    (id, tenant_id, rule_code, intent, category, queue_side, assignee_group, priority, impact, urgency,
     response_minutes, resolve_minutes, version, enabled, create_time, update_time, deleted)
VALUES
    (20290001, 1, 'R-DOOR-NOT-OPEN', 'DOOR_NOT_OPEN', 'DOOR_FAULT', 'ONLINE', 'ONLINE',
     'P2', 'SINGLE', 'HIGH', 30, 120, 1, 1, NOW(3), NOW(3), 0),
    (20290002, 1, 'R-DOOR-NOT-CLOSE', 'DOOR_NOT_CLOSE', 'DOOR_NOT_CLOSED', 'ONLINE', 'ONLINE',
     'P1', 'SINGLE', 'HIGH', 10, 120, 1, 1, NOW(3), NOW(3), 0),
    (20290003, 1, 'R-ITEM-MISSING', 'ITEM_MISSING', 'ITEM_DISPUTE', 'ONLINE', 'ONLINE',
     'P1', 'SINGLE', 'HIGH', 10, 120, 1, 1, NOW(3), NOW(3), 0),
    (20290004, 1, 'R-ITEM-LEFT', 'ITEM_LEFT', 'ITEM_LEFT', 'ONLINE', 'ONLINE',
     'P2', 'SINGLE', 'NORMAL', 30, 240, 1, 1, NOW(3), NOW(3), 0),
    (20290005, 1, 'R-DEVICE-OFFLINE', 'DEVICE_OFFLINE', 'DEVICE_OFFLINE', 'ONLINE', 'ONLINE',
     'P1', 'CABINET', 'HIGH', 10, 120, 1, 1, NOW(3), NOW(3), 0),
    (20290006, 1, 'R-BILLING', 'BILLING_DISPUTE', 'BILLING_DISPUTE', 'ONLINE', 'ONLINE',
     'P2', 'ACCOUNT', 'HIGH', 30, 240, 1, 1, NOW(3), NOW(3), 0),
    (20290007, 1, 'R-PICKUP-CODE', 'PICKUP_CODE', 'PICKUP_CODE', 'ONLINE', 'ONLINE',
     'P2', 'SINGLE', 'NORMAL', 30, 240, 1, 1, NOW(3), NOW(3), 0),
    (20290008, 1, 'R-OTHER', 'OTHER', 'UNSORTED', 'CUSTOMER_SERVICE', 'CUSTOMER_SERVICE',
     'P3', 'SINGLE', 'NORMAL', 120, 1440, 1, 1, NOW(3), NOW(3), 0)
ON DUPLICATE KEY UPDATE category = VALUES(category), queue_side = VALUES(queue_side),
    assignee_group = VALUES(assignee_group), priority = VALUES(priority), impact = VALUES(impact),
    urgency = VALUES(urgency), response_minutes = VALUES(response_minutes),
    resolve_minutes = VALUES(resolve_minutes), enabled = VALUES(enabled);
