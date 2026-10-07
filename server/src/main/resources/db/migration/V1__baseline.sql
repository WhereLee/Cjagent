-- ============================================================================
-- V1: 底座基线表（不含换电业务）
-- 约定：utf8mb4 / InnoDB / 主键 BIGINT 雪花 ID / 审计列由 MetaObjectHandler 填充
-- 说明：sys_operation_log 的写入方（@OperationLog 切面）在第四刀落地，
--       表结构先定下来，避免到时为了加字段回头改已上线的表。
-- ============================================================================

CREATE TABLE IF NOT EXISTS sys_operation_log
(
    id            BIGINT       NOT NULL COMMENT '雪花 ID',
    tenant_id     BIGINT       NULL COMMENT '租户 ID，平台侧操作可为空',
    module        VARCHAR(64)  NOT NULL COMMENT '业务模块，如 tenant / rider',
    operation     VARCHAR(64)  NOT NULL COMMENT '操作描述，如 创建租户',
    request_uri   VARCHAR(255) NULL COMMENT '请求路径',
    request_method VARCHAR(10) NULL COMMENT 'HTTP 方法',
    operator_id   BIGINT       NULL COMMENT '操作人 ID',
    operator_name VARCHAR(64)  NULL COMMENT '操作人名称快照，改名后日志仍可读',
    params        TEXT         NULL COMMENT '入参摘要（必须脱敏，禁止存明文手机号/身份证）',
    success       TINYINT      NOT NULL DEFAULT 1 COMMENT '1 成功 0 失败',
    error_msg     VARCHAR(512) NULL COMMENT '失败摘要',
    cost_ms       BIGINT       NULL COMMENT '耗时毫秒',
    trace_id      VARCHAR(64)  NULL COMMENT '链路 ID，可与日志关联',
    ip            VARCHAR(64)  NULL COMMENT '来源 IP',
    create_time   DATETIME     NOT NULL COMMENT '操作时间',
    PRIMARY KEY (id),
    KEY idx_tenant_time (tenant_id, create_time),
    KEY idx_trace (trace_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='操作审计日志';
