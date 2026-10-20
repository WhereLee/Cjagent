-- ============================================================================
-- V34：后台订单查询（运营台）的权限点与手机号索引
--
-- 后台原先把"异常格口 / 欠费 / 押金 / 工单"都做了，唯独没有"按订单号找一张单"——
-- 而客服接到的第一句话通常是"我的订单号是 xxx，东西取不出来"。
--
-- 权限点沿用 V12 的取向：只播种权限点本身，授权给"已经拥有 system:user:list 的角色"
-- （子查询定位，不写死角色 ID）。本功能全是只读，所以只有一个 MENU 点，没有写权限。
--
-- 索引只加一张：biz_customer 原先只有 open_id 与 tenant 索引，按手机号精确查单会扫
-- 该租户全部客户。订单表不用加：V4 已有 idx_order_tenant_time(tenant_id, create_time)，
-- 列表默认按创建时间倒序正好命中。
-- ============================================================================

INSERT INTO sys_permission (id, parent_id, code, name, type, path, sort, status, create_time, update_time, deleted)
VALUES (20280001, 0, 'order:list', '订单查询', 'MENU', '/orders', 55, 1, NOW(3), NOW(3), 0)
ON DUPLICATE KEY UPDATE name = VALUES(name), path = VALUES(path), sort = VALUES(sort), update_time = NOW(3);

INSERT INTO sys_role_permission (id, tenant_id, role_id, permission_id, create_time, update_time, deleted)
SELECT 20281000 + p.id, g.tenant_id, g.role_id, p.id, NOW(3), NOW(3), 0
FROM (SELECT DISTINCT rp.tenant_id, rp.role_id
      FROM sys_role_permission rp
               JOIN sys_permission sp ON sp.id = rp.permission_id
      WHERE sp.code = 'system:user:list'
        AND rp.deleted = 0
        AND sp.deleted = 0) g,
     sys_permission p
WHERE p.id = 20280001
  AND p.deleted = 0
ON DUPLICATE KEY UPDATE update_time = NOW(3);

ALTER TABLE biz_customer
    ADD KEY idx_customer_phone (tenant_id, phone);
