-- 查看明文手机号的权限点。
--
-- 背景：手机号在本项目里是唯一真实的个人敏感字段，而"谁能看到明文"此前靠调用方自觉
-- （AI 工具层自己负责把它滤掉）。这条迁移把它变成机器守：默认脱敏，有权限点才返回明文。
--
-- 给谁：线上业务员（online_ops）与线下运维员（field_ops）——他们的工作方式里本来就要
-- 给人打电话（客服回访、现场找不到人）。技术运维不给：排障看日志与指标，看不到手机号
-- 不影响干活。业务管理员是按业务身份划分的概念，RBAC 里目前没有对应角色；将来新增
-- 管理类角色时要一并授予这一项，否则后台对那些人会一直显示脱敏值。
--
-- 判断只在服务层做：明文不出服务器，而不是发到浏览器再脱敏（那等于没保护）。
-- 判定实现见 com.wherelee.cabinet.platform.security.PhoneVisibility。

INSERT INTO sys_permission (id, parent_id, code, name, type, path, sort, status, create_time, update_time, deleted)
VALUES (20274001, 0, 'customer:phone:view', '查看明文手机号', 'API', NULL, 90, 1, NOW(3), NOW(3), 0)
ON DUPLICATE KEY UPDATE name = VALUES(name);

-- 授予现有两个作业类角色。用 role_id + permission_id 定位，不依赖 sys_role_permission 的自增主键。
INSERT INTO sys_role_permission (id, tenant_id, role_id, permission_id, create_time, update_time, deleted)
VALUES (20274101, 1, 20271001, 20274001, NOW(3), NOW(3), 0),
       (20274102, 1, 20271002, 20274001, NOW(3), NOW(3), 0)
ON DUPLICATE KEY UPDATE role_id = VALUES(role_id);
