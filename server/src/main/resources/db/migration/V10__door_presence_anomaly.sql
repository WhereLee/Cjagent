-- ============================================================================
-- V10：门态、柜内物检与格口异常（docs/门态与物品争议设计.md §2/§3/§4）
--
-- 一句话动机：**门关了不等于柜内是空的**。此前流程把这两件事当一件，
-- 于是出现"东西还在柜里、订单却结束了、格口还卖给了下一个人"。
--
-- 为什么加列而不是给 status 加一个 'DOOR_OPEN' 枚举值：
-- "这个格口能不能卖" 与 "门开着没 / 里面有没有东西" 是**正交事实**。
-- 挤进一个字段就丢掉了最需要分辨的组合——门开着但里面已经有件（投件后走了）。
-- 第 8/12 刀把 RESERVED 与 OCCUPIED 拆开是同一个道理。
--
-- 门态用时间戳而不是布尔：door_open_at 同时回答"开着吗"和"开了多久"，
-- 计费起点与提醒时长都要后者。
--
-- 异常只覆盖"门与物"四类；硬件故障与人工停用继续走 status=FAULT/MAINTENANCE，
-- 对账发现的归属异常继续走对账差异。故意不给一个"万能异常字段"，
-- 否则会出现"格口同时是 MAINTENANCE 又是 CONTENT_LEFT"这种两处真相、说不清谁优先的状态。
--
-- 解除留痕不在这里加列：状态表只存"当前异常"，谁解除的、什么时候、为什么由
-- biz_fault_event 流水承载（第 11 刀定的取向），避免两处记同一件事还记歪。
-- ============================================================================

ALTER TABLE biz_compartment
    ADD COLUMN door_open_at DATETIME(3) NULL COMMENT '门打开的起始时刻；NULL=当前是关着的。计费起点与提醒时长都据它'
        AFTER current_order_id,
    ADD COLUMN door_opened_by BIGINT NULL COMMENT '这次是谁开的门（客户 ID；后台强制开柜另记审计）'
        AFTER door_open_at,
    ADD COLUMN door_closed_at DATETIME(3) NULL COMMENT '最近一次关门时刻；门开着时为 NULL'
        AFTER door_opened_by,
    ADD COLUMN door_closed_by BIGINT NULL COMMENT '谁关的门：可能本人，也可能是下一位顺手代关的客户（纠纷还原用）'
        AFTER door_closed_at,
    -- 三值而不是布尔：真实物检会给出"不知道"（离线、传感器坏、脏污）。
    -- 把 UNKNOWN 折成 true/false 就是在最该谨慎的地方造假——折成"没东西"会卖错格子，
    -- 折成"有东西"会把空柜永久锁死。
    ADD COLUMN presence VARCHAR(16) NULL COMMENT '最近一次柜内物检结论：PRESENT / ABSENT / UNKNOWN'
        AFTER door_closed_by,
    ADD COLUMN presence_checked_at DATETIME(3) NULL COMMENT '物检时刻。设备"看一眼"的时刻不等于"现在"，过期结论不得参与判定'
        AFTER presence,
    ADD COLUMN anomaly VARCHAR(24) NULL COMMENT '格口异常原因（门/物四类）；NULL=无异常。硬件故障走 status'
        AFTER presence_checked_at,
    ADD COLUMN anomaly_at DATETIME(3) NULL COMMENT '异常判定时刻（台账排序与停留时长统计用）'
        AFTER anomaly,
    ADD COLUMN anomaly_reason VARCHAR(255) NULL COMMENT '异常说明：业务运维到场要看的是这句，不是枚举名'
        AFTER anomaly_at;

-- CHECK 与枚举同步（沿用第 8 刀的做法：枚举加了值忘了改这里，脏数据就先落库）
ALTER TABLE biz_compartment
    ADD CONSTRAINT ck_comp_presence CHECK (
        presence IS NULL OR presence IN ('PRESENT', 'ABSENT', 'UNKNOWN')
    );

ALTER TABLE biz_compartment
    ADD CONSTRAINT ck_comp_anomaly CHECK (
        anomaly IS NULL OR anomaly IN ('DOOR_OPEN', 'CONTENT_LEFT', 'CONTENT_UNVERIFIED', 'SENSOR_CONFLICT')
    );

-- "开了又关了但起点没清"是代码写错才能到的状态；
-- 反过来"closed 非空 + open 为空"是正常稳态（跑完一次开→关之后就该这样），不能限掉。
ALTER TABLE biz_compartment
    ADD CONSTRAINT ck_comp_door_state CHECK (
        NOT (door_open_at IS NOT NULL AND door_closed_at IS NOT NULL)
    );

-- ---------------- 寄存单：容错期与结束原因 ----------------

ALTER TABLE biz_storage_order
    -- 容错到期时刻按单存，而不是每次现算 open_at + 配置值：
    -- 运营中途把容错从 5 分钟改成 10 分钟，不得追溯改变进行中那张单该从几点开始计
    ADD COLUMN tolerance_until DATETIME(3) NULL COMMENT '开门容错到期时刻；到期仍未关门即从此刻开始计费'
        AFTER expected_finish_at,
    ADD COLUMN close_reason VARCHAR(24) NULL COMMENT '结束原因：NORMAL / ABANDONED（声明放弃物品）/ REMOTE（远程结束）/ DISPUTE_WAIVED（判为设备误报免责）'
        AFTER tolerance_until,
    -- 加收额冗余在单上：账单要能一眼解释"这笔多收的是什么"，而不是让人去反推流水
    ADD COLUMN remote_close_points BIGINT NOT NULL DEFAULT 0 COMMENT '远程结束/超窗重开的加收点数（=该格口单价 × remote-close-hours）'
        AFTER close_reason;

ALTER TABLE biz_storage_order
    ADD CONSTRAINT ck_order_close_reason CHECK (
        close_reason IS NULL OR close_reason IN ('NORMAL', 'ABANDONED', 'REMOTE', 'DISPUTE_WAIVED')
    );

-- 不建索引并说清理由，免得日后被"顺手补一个"：
-- 格口表总行数被柜机数钉死（180×24≈4320），异常/未关门的扫描再差也是几千行；
-- 分配查询本来就走 idx_slot_acquire，新条件只是在这 24 行的小范围里多过滤一次。
-- 低基数列上补索引在这张表里是负收益。
