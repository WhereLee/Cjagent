-- ============================================================================
-- V21：作废“免除争议费用（判定设备误报）”权限点
--
-- 背景（用户 2026-10-12 定）：19A 把结束改成一律不拦人之后，“争议期间”这个概念就不存在了
-- ——用户点结束当场结算，不存在“平台在等判定的那段收费时间”。于是 V12 播下来的
-- `locker:order:waive-fee` 这个动钱的权限点失去了它的靶子，对应的端点与服务方法都已删除。
--
-- 为什么软删（deleted=1）而不是 DELETE：
--   ① RBAC 装载普遍带 deleted=0 条件，软删与既有语义一致，不用赌有没有代码在物理读；
--   ② 权限点是审计对象。哪张角色曾经拥有“免单”资格，是纠纷复盘要查的东西，
--      物理删行等于把这段历史抹掉——而我们这一路的原则是“留底”。
--   ③ 万一这条规则要回来（比如以后立“计费起点谎报的申诉与退还”），行还在，恢复是一次 UPDATE。
-- ============================================================================

UPDATE sys_role_permission
   SET deleted = 1, update_time = NOW(3)
 WHERE deleted = 0
   AND permission_id IN (SELECT id FROM sys_permission WHERE code = 'locker:order:waive-fee' AND deleted = 0);

UPDATE sys_permission
   SET deleted = 1, update_time = NOW(3)
 WHERE code = 'locker:order:waive-fee' AND deleted = 0;

-- 不留一句空话：这一刀之后库里不该再有任何“可免单”的授权关系。
-- 校验方式（人工执行）：
--   select count(*) from sys_role_permission rp join sys_permission p on p.id = rp.permission_id
--    where p.code = 'locker:order:waive-fee' and rp.deleted = 0 and p.deleted = 0;   -- 期望 0
