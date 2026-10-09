-- ============================================================================
-- V8: 点数流水类型注释与代码对齐（第 12 刀）
--
-- V4 里 biz_type 的注释写的是 RECHARGE / FREEZE / SETTLE / UNFREEZE / REFUND / ADJUST。
-- 实现时把"冻结"拆成了成对的两笔（FREEZE_OUT 影响可用栏、FREEZE_IN 影响冻结栏），
-- 因为**一笔流水只能影响一栏**才能让"Σ流水 == 余额"变成两条纯求和断言（见 PointTxnType）。
--
-- 这里只改注释：列本身是 VARCHAR(24) 且无 CHECK，扩取值不需要结构变更。
-- 但注释必须跟着改——**注释也是版本化的文档**，代码与注释漂移的下场是
-- 下一个人按注释写出一笔跨两栏的流水，把不变量悄悄打断。
-- ============================================================================

ALTER TABLE biz_point_txn
    MODIFY COLUMN biz_type VARCHAR(24) NOT NULL
        COMMENT 'RECHARGE/ADJUST/CONSUME/FREEZE_OUT/FREEZE_IN/UNFREEZE_OUT/UNFREEZE_IN（一笔只动一栏，符号由类型定）';
