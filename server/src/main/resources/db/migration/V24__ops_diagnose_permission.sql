-- ============================================================================
-- V24：运维 AI 诊断权限点（第 24 刀，2026-10-13 用户授权）
--
-- 为什么是一个权限点：运维诊断（health/日志/崩溃残留/故障台账的 AI 归纳）是
-- "读服务器内部状态"的能力，与业务侧权限（workorder/locker/pricing）不共用语义——
-- 能看工单的人未必该看 GC 日志。按 V17 先例进权限字典，授权走 field_ops：
-- 服务器运维与现场运维当前由同一人承担（单人项目，不新建角色）。
--
-- agent 的边界在代码里（OpsTools 零写工具），不在权限里：这个权限点只放行"要一份
-- 诊断报告"，报告里的建议永远是草稿文本，执行仍是人（运维手册 §7 三板斧）。
-- ============================================================================

INSERT INTO sys_permission (id, parent_id, code, name, type, path, sort, status, create_time, update_time, deleted)
VALUES (20273001, 0, 'ops:diagnose', '运维AI诊断', 'API', NULL, 80, 1, NOW(3), NOW(3), 0)
ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO sys_role_permission (id, tenant_id, role_id, permission_id, create_time, update_time, deleted)
VALUES (20273002, 1, 20271002, 20273001, NOW(3), NOW(3), 0)
ON DUPLICATE KEY UPDATE permission_id = VALUES(permission_id);
