-- 测试专用表：只为验证 BaseEntity 审计字段自动填充与逻辑删除，不进正式迁移链
CREATE TABLE IF NOT EXISTS probe_audit
(
    id          BIGINT      NOT NULL COMMENT '雪花 ID',
    name        VARCHAR(64) NOT NULL COMMENT '测试用名称',
    tenant_id   BIGINT      NULL COMMENT '租户 ID',
    create_time DATETIME(3)  NULL COMMENT '创建时间（毫秒精度，否则测不出 updateTime 刷新）',
    update_time DATETIME(3)  NULL COMMENT '更新时间',
    deleted     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='审计填充探针表';
