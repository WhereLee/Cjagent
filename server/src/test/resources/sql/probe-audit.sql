-- 测试夹具表：只用于验证 BaseEntity 审计字段自动填充与租户隔离。
--
-- 为什么不用 Flyway：测试脚本若也进迁移版本链，就会和正式迁移争抢版本号
-- （曾经把这条表命名为 V100，导致后来新增的 V2 变成 out-of-order，validate 直接失败）。
-- 夹具该由测试自己准备、自己清理，所以这里用 @Sql 执行，不进迁移链。
CREATE TABLE IF NOT EXISTS probe_audit
(
    id          BIGINT      NOT NULL COMMENT '雪花 ID',
    name        VARCHAR(64) NOT NULL COMMENT '测试用名称',
    tenant_id   BIGINT      NULL COMMENT '租户 ID',
    create_time DATETIME(3) NULL COMMENT '创建时间（毫秒精度，否则测不出 updateTime 刷新）',
    update_time DATETIME(3) NULL COMMENT '更新时间',
    deleted     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='审计与租户探针表';
