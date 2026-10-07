-- ============================================================================
-- V3: 认证授权基线（RBAC 五表 + 骑手账号 + 平台根租户）
--
-- 隔离口径（重要，和 CabinetTenantHandler 的白名单成对）：
--   · 带 tenant_id、受租户拦截器自动过滤：sys_user / sys_role / sys_user_role /
--     sys_role_permission / sys_rider
--   · 全局共享、进白名单：sys_permission（菜单与权限点是平台维护的字典，租户不自定义）
--
-- 不在这里播种任何账号或口令：口令必须由运维在部署后创建，仓库里不留可用凭据。
-- ============================================================================

-- 平台根租户：内置角色与平台侧账号的归属
INSERT INTO sys_tenant (id, tenant_code, name, status, create_time, update_time, deleted)
VALUES (1, 'platform', '平台自营', 1, NOW(3), NOW(3), 0)
ON DUPLICATE KEY UPDATE tenant_code = VALUES(tenant_code);

-- 权限点/菜单字典（全局共享）
CREATE TABLE IF NOT EXISTS sys_permission
(
    id            BIGINT       NOT NULL COMMENT '雪花 ID',
    parent_id     BIGINT       NOT NULL DEFAULT 0 COMMENT '父节点，0 为根',
    code          VARCHAR(128) NOT NULL COMMENT '权限编码，如 system:user:list',
    name          VARCHAR(64)  NOT NULL COMMENT '显示名',
    type          VARCHAR(16)  NOT NULL COMMENT 'MENU / BUTTON / API',
    path          VARCHAR(255) NULL COMMENT '前端路由（type=MENU 时使用）',
    sort          INT          NOT NULL DEFAULT 0 COMMENT '排序',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1 启用 0 停用',
    create_time   DATETIME(3)  NOT NULL COMMENT '创建时间',
    update_time   DATETIME(3)  NOT NULL COMMENT '更新时间',
    deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_permission_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='权限点与菜单字典（全局共享，无 tenant_id）';

-- 租户
CREATE TABLE IF NOT EXISTS sys_role
(
    id          BIGINT       NOT NULL COMMENT '雪花 ID',
    tenant_id   BIGINT       NOT NULL COMMENT '租户 ID',
    role_code   VARCHAR(64)  NOT NULL COMMENT '角色编码，租户内唯一',
    role_name   VARCHAR(64)  NOT NULL COMMENT '角色名称',
    remark      VARCHAR(255) NULL COMMENT '备注',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1 启用 0 停用',
    create_time DATETIME(3)  NOT NULL COMMENT '创建时间',
    update_time DATETIME(3)  NOT NULL COMMENT '更新时间',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_tenant_code (tenant_id, role_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='角色';

CREATE TABLE IF NOT EXISTS sys_user
(
    id            BIGINT       NOT NULL COMMENT '雪花 ID',
    tenant_id     BIGINT       NOT NULL COMMENT '租户 ID',
    username      VARCHAR(64)  NOT NULL COMMENT '登录名，租户内唯一（不同租户可同名）',
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 摘要，绝不明文；! 开头表示禁用登录',
    real_name     VARCHAR(64)  NULL COMMENT '姓名',
    phone         VARCHAR(32)  NULL COMMENT '联系电话（PII，返回需脱敏）',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1 启用 0 停用',
    last_login_at DATETIME(3)  NULL COMMENT '最后登录时间',
    create_time   DATETIME(3)  NOT NULL COMMENT '创建时间',
    update_time   DATETIME(3)  NOT NULL COMMENT '更新时间',
    deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_tenant_username (tenant_id, username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='后台用户';

CREATE TABLE IF NOT EXISTS sys_user_role
(
    id          BIGINT      NOT NULL COMMENT '雪花 ID',
    tenant_id   BIGINT      NOT NULL COMMENT '租户 ID（冗余用于隔离，取值同所属用户）',
    user_id     BIGINT      NOT NULL COMMENT '用户 ID',
    role_id     BIGINT      NOT NULL COMMENT '角色 ID',
    create_time DATETIME(3) NOT NULL COMMENT '创建时间',
    update_time DATETIME(3) NOT NULL COMMENT '更新时间',
    deleted     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_role (user_id, role_id),
    KEY idx_role_id (role_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='用户-角色关联';

CREATE TABLE IF NOT EXISTS sys_role_permission
(
    id            BIGINT      NOT NULL COMMENT '雪花 ID',
    tenant_id     BIGINT      NOT NULL COMMENT '租户 ID（取值同所属角色）',
    role_id       BIGINT      NOT NULL COMMENT '角色 ID',
    permission_id BIGINT      NOT NULL COMMENT '权限点 ID',
    create_time   DATETIME(3) NOT NULL COMMENT '创建时间',
    update_time   DATETIME(3) NOT NULL COMMENT '更新时间',
    deleted       TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_permission (role_id, permission_id),
    KEY idx_permission_id (permission_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='角色-权限关联';

-- 骑手账号（小程序端登录主体）
CREATE TABLE IF NOT EXISTS sys_rider
(
    id            BIGINT       NOT NULL COMMENT '雪花 ID',
    tenant_id     BIGINT       NOT NULL COMMENT '所属租户（运营商），换电订单与套餐都挂在这条链上',
    open_id       VARCHAR(64)  NOT NULL COMMENT '微信小程序 openid，全局唯一',
    union_id      VARCHAR(64)  NULL COMMENT '开放平台 unionid（未绑定时为空）',
    phone         VARCHAR(32)  NULL COMMENT '手机号（PII，返回需脱敏）',
    nickname      VARCHAR(64)  NULL COMMENT '昵称',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1 正常 0 冻结',
    register_time DATETIME(3)  NOT NULL COMMENT '首次登录时间',
    last_login_at DATETIME(3)  NULL COMMENT '最后登录时间',
    create_time   DATETIME(3)  NOT NULL COMMENT '创建时间',
    update_time   DATETIME(3)  NOT NULL COMMENT '更新时间',
    deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_rider_open_id (open_id),
    KEY idx_rider_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='骑手账号';
