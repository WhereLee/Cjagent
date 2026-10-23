-- 删掉 estimate_minutes（用户下单时填的“打算存多久”）。
--
-- 19A 把计费改成“预收 4 小时 + 取件按实际时长多退少补、封顶按 capDays”之后，
-- 这一列不再决定任何东西：建单时写 0（原代码注释：“免得以后的代码拿它当用户许下的时长”
-- ），出参回显恒 0，前端文案为了自洽写着“仅用于记录”而实际不留。
--
-- 热表与归档表在 V4 里是同构的，两张都有这一列，必须一起删：
-- 归档作业按“同构”做 INSERT...SELECT，只删一边会让两表列不再对齐。
--
-- 不可逆：老订单里存的预估时长会随列一起消失。那份数据当前没有任何代码读；
-- 若以后要分析“用户以为存多久 vs 实际存多久”，需要重新采集，不能指望回读这一列。
--
-- 注意 expected_finish_at 不受影响：它是 start + capDays 派生出来的超时扫描锚点，
-- 与预估时长无关（见 OrderDeviceOutcomeService 里 setExpectedFinishAt 的算法）。

ALTER TABLE biz_storage_order
    DROP COLUMN estimate_minutes;

ALTER TABLE biz_storage_order_archive
    DROP COLUMN estimate_minutes;
