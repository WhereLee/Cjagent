-- 待投递的事实（transactional outbox）。
--
-- 为什么需要它：`SlotAnomalyPublisher` 在写异常态之后要开工单、发站内消息，
-- 而这些扇出原来是在**同一个业务事务里同步调用**的。下游一抛异常（例如撞
-- uk_work_order_open），Spring 会把共享的物理事务标成 rollback-only，
-- 于是"标异常 + 写故障流水"甚至整个取件/上报请求一起回滚——用户什么都没做错，
-- 却拿不到东西，只因为工单侧抖了一下。在那个上下文里加 try/catch 是无效的。
--
-- 做法：把"派生动作"（开工单、发通知）变成一行本地记录——同一个事务里写本地表不会失败——
-- 再由中继异步投递。消费者本来就幂等（工单靠唯一索引、消息靠 dedup_key），重复投递无害。
--
-- 为什么独立一张表而不是复用 biz_delay_task：那张表的行是"到点要做的事"
-- （fire_at / recurring / 每类任务的重试预算），这里的行是"已经发生、待投递的事实"。
-- 混在一张表里，fire_at 对事实没意义、载荷列对延迟任务没意义，以后加字段互相干扰。
--
-- 不做归档：行数增长取决于异常发生频次（小时级巡检 + 事件驱动），远低于订单量；
-- 若将来它成为热表，再按 done_at 冷热分层，本迁移不预做。

CREATE TABLE IF NOT EXISTS biz_outbox
(
    id              BIGINT      NOT NULL COMMENT '雪花 ID',
    tenant_id       BIGINT      NOT NULL COMMENT '运营商 ID（投递时在该租户上下文里执行）',
    event_type      VARCHAR(32) NOT NULL COMMENT 'WORK_ORDER_ANOMALY / OWNER_NOTICE',
    dedup_key       VARCHAR(96) NOT NULL COMMENT '同一事实不重复入队；与 event_type 组成唯一键',
    payload         VARCHAR(2048) NOT NULL COMMENT '投递所需的事实快照（JSON）',
    status          VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / DONE / FAILED / DEAD',
    attempt         INT         NOT NULL DEFAULT 0 COMMENT '已尝试次数（指数退避依据）',
    next_attempt_at DATETIME(3) NOT NULL COMMENT '下次可投时间；失败后退避推远',
    last_error      VARCHAR(255) NULL COMMENT '最近失败原因',
    done_at         DATETIME(3) NULL COMMENT '投递成功时刻',
    create_time     DATETIME(3) NOT NULL COMMENT '创建时间',
    update_time     DATETIME(3) NOT NULL COMMENT '更新时间',
    deleted         TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    -- 同一事实只入队一次：重复标同一种异常不会堆出多条待投递
    UNIQUE KEY uk_outbox_fact (event_type, dedup_key),
    -- 中继扫描口径：待投且到时间
    KEY idx_outbox_due (status, next_attempt_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='待投递的事实（outbox）：与业务写同事务落行，由中继投递';
