-- ============================================================================
-- V2: 租户主表 sys_tenant（SaaS 底座，不含换电业务）
--
-- 说明：
-- 1. sys_tenant 自身的 id 就是其它业务表的 tenant_id 取值，不再另设业务编码列。
-- 2. 本表在租户拦截器白名单里（它自己不能再被 tenant_id 条件过滤）。
-- 3. contact_phone 属 PII：入库明文，返回给前端必须走 @JsonMask（第四刀）。
-- ============================================================================

CREATE TABLE IF NOT EXISTS sys_tenant
(
    id            BIGINT       NOT NULL COMMENT '租户 ID（= 业务表 tenant_id）',
    tenant_code   VARCHAR(64)  NOT NULL COMMENT '租户编码，全局唯一，用于登录与运维定位',
    name          VARCHAR(128) NOT NULL COMMENT '租户名称（商户/运营商）',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1 启用 0 停用；停用后该租户所有请求应被拒绝',
    contact_phone VARCHAR(32)  NULL COMMENT '联系电话（PII，返回需脱敏）',
    expire_date   DATE         NULL COMMENT '服务到期日，空表示未设到期',
    create_time   DATETIME(3)  NOT NULL COMMENT '创建时间',
    update_time   DATETIME(3)  NOT NULL COMMENT '更新时间',
    deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0 正常 1 已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tenant_code (tenant_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='租户主表';
