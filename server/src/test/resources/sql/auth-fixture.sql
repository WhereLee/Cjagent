-- 认证测试夹具：两个租户 + 权限点 + 角色 + 角色权限绑定 + 后台账号。
--
-- 走 @Sql 直连 DataSource，不经过 MyBatis 拦截器，因此这里的插入<b>不受租户上下文约束</b>
-- （夹具要跨租户准备数据，本来就该如此）。
-- 用 ON DUPLICATE KEY 保证可反复执行：@Sql 是否随测试事务回滚取决于配置，脚本自身必须幂等。
-- 账号口令由测试用例用 PasswordEncoder 现算再 update，仓库里不存任何可用口令。

INSERT INTO sys_tenant (id, tenant_code, name, status, create_time, update_time, deleted)
VALUES (8101, 't-one', '测试租户一', 1, NOW(3), NOW(3), 0)
ON DUPLICATE KEY UPDATE name = VALUES(name), status = 1, deleted = 0;

INSERT INTO sys_tenant (id, tenant_code, name, status, create_time, update_time, deleted)
VALUES (8102, 't-two', '测试租户二', 1, NOW(3), NOW(3), 0)
ON DUPLICATE KEY UPDATE name = VALUES(name), status = 1, deleted = 0;

-- 权限点（全局字典，白名单表）
INSERT INTO sys_permission (id, parent_id, code, name, type, sort, status, create_time, update_time, deleted)
VALUES (9001, 0, 'probe:read', '探针读权限', 'API', 1, 1, NOW(3), NOW(3), 0)
ON DUPLICATE KEY UPDATE name = VALUES(name), status = 1, deleted = 0;

-- 角色 OPS（属于租户 8101）与它的权限绑定
INSERT INTO sys_role (id, tenant_id, role_code, role_name, status, create_time, update_time, deleted)
VALUES (9101, 8101, 'OPS', '运维', 1, NOW(3), NOW(3), 0)
ON DUPLICATE KEY UPDATE role_name = VALUES(role_name), status = 1, deleted = 0;

INSERT INTO sys_role_permission (id, tenant_id, role_id, permission_id, create_time, update_time, deleted)
VALUES (9201, 8101, 9101, 9001, NOW(3), NOW(3), 0)
ON DUPLICATE KEY UPDATE deleted = 0;

-- 两个租户下的同名账号（用户名只租户内唯一，专门用来验证登录不会跨租户串号）
INSERT INTO sys_user (id, tenant_id, username, password_hash, real_name, status, create_time, update_time, deleted)
VALUES (9301, 8101, 'ops-admin', 'PLACEHOLDER', '运维一', 1, NOW(3), NOW(3), 0)
ON DUPLICATE KEY UPDATE real_name = VALUES(real_name), status = 1, deleted = 0;

INSERT INTO sys_user (id, tenant_id, username, password_hash, real_name, status, create_time, update_time, deleted)
VALUES (9302, 8102, 'ops-admin', 'PLACEHOLDER', '运维二', 1, NOW(3), NOW(3), 0)
ON DUPLICATE KEY UPDATE real_name = VALUES(real_name), status = 1, deleted = 0;

-- 9301 绑定 OPS 角色；9302 故意不绑任何角色，用来测"已登录但权限不足 → 403"
INSERT INTO sys_user_role (id, tenant_id, user_id, role_id, create_time, update_time, deleted)
VALUES (9401, 8101, 9301, 9101, NOW(3), NOW(3), 0)
ON DUPLICATE KEY UPDATE deleted = 0;
