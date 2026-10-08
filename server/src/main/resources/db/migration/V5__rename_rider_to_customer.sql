-- ============================================================================
-- V5: 身份表改名 sys_rider → biz_customer（领域从换电柜切到储物柜，S-13）
--
-- 为什么用 RENAME 而不是"建新表 + 迁数据 + 删旧表"：
--   ① RENAME 是元数据操作，瞬时完成、不复制行，测试与开发库里的既有账号原样保留；
--   ② 索引、约束、统计信息都跟着表走，不会在迁移中悄悄丢掉 uk(open_id) 这个唯一约束；
--   ③ 两段式（新表+双写+切换）是为零停机在线迁移准备的，本项目没有生产流量，
--      用它只会增加出错面。
--
-- 已发布的 V3 不改动（迁移脚本一经发布不可回改），改名只由本版本负责。
-- 注意 Java 侧同步改名：SysRider→BizCustomer、SysRiderMapper→BizCustomerMapper、
-- RiderAuthoritiesResolver→CustomerAuthoritiesResolver，角色 ROLE_RIDER→ROLE_CUSTOMER。
-- 接口路径 /api/mini/auth/* 与"端"标识 mini 保留：mini 指客户端这一端，不是换电语义。
-- ============================================================================

RENAME TABLE sys_rider TO biz_customer;

ALTER TABLE biz_customer
    RENAME INDEX uk_rider_open_id TO uk_customer_open_id,
    RENAME INDEX idx_rider_tenant TO idx_customer_tenant;

ALTER TABLE biz_customer COMMENT ='寄存客户（用户端登录主体，前身 sys_rider）';
