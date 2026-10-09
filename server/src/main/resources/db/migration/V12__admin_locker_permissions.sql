-- ============================================================================
-- V12：运营后台（第 14 刀）的权限点
--
-- 为什么单独一条迁移：权限点是**全局字典**（无 tenant_id，进白名单，见 V3 的口径说明），
-- 新增受保护接口必须同时新增权限点，否则只能借用 `system:*` 那种大权限——
-- 而"运营能重建空闲集合"、"客服能免除争议费用"、"管理员能改账号"是三件不同的事，
-- 合成一个权限点等于任何人都能"顺手改一下状态"把钱免了。
--
-- 只播种权限点本身 + 授权给"已经拥有 system:user:list 的那些角色"（子查询定位，不写死 ID）：
-- 写死角色 ID 会让本迁移在别的库上把新权限静默授给错误的角色，那比不授权更危险。
-- 沿用 V3 的取向：不播种任何账号或口令。
-- ============================================================================

-- 台账与清单（只读）
INSERT INTO sys_permission (id, parent_id, code, name, type, path, sort, status, create_time, update_time, deleted)
VALUES
    (20260001, 0, 'locker:ledger:list',              '异常格口台账', 'MENU', '/locker/ledger',    60, 1, NOW(3), NOW(3), 0),
    (20260002, 0, 'locker:deposit:unrefunded-list',  '未退押金清单', 'MENU', '/locker/deposits',  61, 1, NOW(3), NOW(3), 0),
    (20260003, 0, 'locker:arrears:list',             '欠费客户清单', 'MENU', '/locker/arrears',   62, 1, NOW(3), NOW(3), 0),
    -- 处置动作（写操作，逐个单独授权）
    (20260010, 0, 'locker:compartment:resolve',      '清柜解除格口异常', 'API', NULL, 63, 1, NOW(3), NOW(3), 0),
    (20260011, 0, 'locker:door:force-open',          '后台强制开柜',       'API', NULL, 64, 1, NOW(3), NOW(3), 0),
    -- 免除争议费用是**动钱**的动作，与"清异常""禁用客户"都分开。
    -- 这里刻意不配任何"额度/上限"参数：金额由 started_at → dispute_started_at 算出，不由人填
    (20260012, 0, 'locker:order:waive-fee',          '免除争议费用',       'API', NULL, 65, 1, NOW(3), NOW(3), 0),
    (20260013, 0, 'locker:customer:disable',         '禁用/启用客户',      'API', NULL, 66, 1, NOW(3), NOW(3), 0),
    -- 重建空闲集合改的是准入用的 Redis 集合，误操作会让一批格口短时不可卖，所以也不借用 system:*
    (20260014, 0, 'locker:freeset:rebuild',          '重建空闲格口集合',   'API', NULL, 67, 1, NOW(3), NOW(3), 0)
ON DUPLICATE KEY UPDATE name = VALUES(name), path = VALUES(path), sort = VALUES(sort), update_time = NOW(3);

INSERT INTO sys_role_permission (id, tenant_id, role_id, permission_id, create_time, update_time, deleted)
SELECT 20261000 + p.id, g.tenant_id, g.role_id, p.id, NOW(3), NOW(3), 0
FROM (SELECT DISTINCT rp.tenant_id, rp.role_id
      FROM sys_role_permission rp
               JOIN sys_permission sp ON sp.id = rp.permission_id
      WHERE sp.code = 'system:user:list'
        AND rp.deleted = 0
        AND sp.deleted = 0) g,
     sys_permission p
WHERE p.id IN (20260001, 20260002, 20260003, 20260010, 20260011, 20260012, 20260013, 20260014)
  AND p.deleted = 0
ON DUPLICATE KEY UPDATE update_time = NOW(3);
