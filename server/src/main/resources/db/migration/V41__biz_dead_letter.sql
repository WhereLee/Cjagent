-- MQ 死信归档。
--
-- 为什么要有这张表：RocketMQ 的死信队列（%DLQ%{consumerGroup}）是"重试 16 次仍失败"的隔离区，
-- 但它**只保留 3 天**。3 天之后，一条没能处理的事实就彻底消失了——那意味着某个格口的异常
-- 永远没有开成工单，而没人知道。归档到自己的库里，证据才是永久的。
--
-- 四件套里这张表负责"归档"，另外三件是：DLQ 消费者（把它写进来）、指标 + ERROR 日志（让它被看见）、
-- 重放接口（让它可以被处理）。只做告警不做归档 = 3 天后证据消失；有归档没重放 = 只能人工造数据。
--
-- 为什么分三列存 topic：死信消息躺在 %DLQ%{group} 里，而重放必须投回**原来的业务 topic + tag**。
-- 只存死信 topic 的话，重放时无从知道该投回哪里。所以：
--   topic      = 原业务 topic（重放目标）
--   tag        = 原 tag（= 事件类型）
--   dlq_topic  = 死信所在 topic（排查与去重用）
--
-- 登记 tenant_id 但仍在白名单里：白名单的意思是“不自动拼租户条件”（运维要看全量），
-- 不是“不需要知道租户”。重放时必须把原来的 tenant_id 随消息带回，
-- 否则消费侧 getUserProperty("tenantId") 拿到 null，连租户都重建不出来。
--
-- 不加 tenant_id 的写法已作废：这是运维数据，不是业务数据。失败的消息可能来自任何租户，
-- 加租户列会让"运维看全量死信"变成跨租户查询。对照 sys_operation_log 的处理——同样进
-- CabinetTenantHandler 的白名单，查的时候自己负责范围。
--
-- msg_key 允许为空：载荷坏到解析不出来的消息，连 KEYS 都可能是空的。唯一键
-- (dlq_topic, msg_key) 在这种情况下不判重（MySQL 对含 NULL 的行不做唯一性约束），
-- 正好是想要的：坏消息本来就不该被去重合并掉。

CREATE TABLE IF NOT EXISTS biz_dead_letter
(
    id              BIGINT        NOT NULL COMMENT '雪花 ID',
    tenant_id       BIGINT        NULL COMMENT '消息所属租户（重放时原样带回）；坏消息可能为空，为空则不可重放',
    topic           VARCHAR(128)  NOT NULL COMMENT '原业务 topic（重放目标）',
    tag             VARCHAR(64)   NOT NULL COMMENT '原 tag（= 事件类型）',
    dlq_topic       VARCHAR(128)  NOT NULL COMMENT '死信所在 topic（%DLQ%组名）',
    msg_key         VARCHAR(96)   NULL COMMENT '消息 KEYS（= outbox 的 dedupKey）；坏消息可能为空',
    payload         VARCHAR(2048) NOT NULL COMMENT '消息体原文',
    error           VARCHAR(255)  NULL COMMENT '消费失败原因（幂等冲突等）',
    reconsume_times INT           NOT NULL DEFAULT 0 COMMENT '已被重试的次数',
    status          VARCHAR(16)   NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN / REPLAYED',
    received_at     DATETIME(3)   NOT NULL COMMENT '进入死信的时刻',
    replayed_at     DATETIME(3)   NULL COMMENT '人工重放的时刻',
    create_time     DATETIME(3)   NOT NULL COMMENT '创建时间',
    update_time     DATETIME(3)   NOT NULL COMMENT '更新时间',
    deleted         TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_dead_letter (dlq_topic, msg_key),
    KEY idx_dead_letter_status (status, received_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='MQ 死信归档（OPEN 待处理 / REPLAYED 已重放）';
