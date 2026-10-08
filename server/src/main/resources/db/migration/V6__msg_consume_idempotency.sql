-- ============================================================================
-- V6: 消息消费幂等表（第 10 刀，异步落库链路的前置）
--
-- 为什么必须先有这张表再写消费者：RocketMQ 是<b>至少一次</b>投递，重复消费是常态而不是异常。
-- 没有消费幂等记录的消费者，只能靠"业务代码自己想办"，而"想办"的结果通常是
-- 插入两条订单、扣两次点数——这类事故在测试环境很难复现，一旦复现就是资损。
--
-- 隔离口径：本表进租户白名单（与 sys_operation_log 同类）。
-- 原因是消费入口必须在"信任消息里的 tenant_id"之前先落一条去重记录——
-- 若这张表带 tenant_id，就会出现"要写幂等记录得先有租户上下文，要有租户上下文得先通过幂等"的环。
-- 代价：查这张表必须自己加 tenant_id 条件，拦截器不会帮。
--
-- 只增不改：status 只允许 PENDING → PROCESSED/FAILED 单向推进，由 Mapper 的条件更新保证；
-- 记录本身不做逻辑删除，否则重放时"删掉的幂等记录"会让第二次消费被当成第一次。
-- ============================================================================

CREATE TABLE IF NOT EXISTS biz_msg_consume
(
    id           BIGINT       NOT NULL COMMENT '雪花 ID',
    topic        VARCHAR(128) NOT NULL COMMENT '消息主题',
    msg_key      VARCHAR(128) NOT NULL COMMENT '业务幂等键（本项目用订单号，不用 broker msgId——重投时 msgId 可能变）',
    tenant_id    BIGINT       NOT NULL COMMENT '消息声称的租户，仅用于事后排查越权投递',
    status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / PROCESSED / FAILED',
    attempt      INT          NOT NULL DEFAULT 0 COMMENT '已处理次数（重试与死信用它定位）',
    trace_id     VARCHAR(64)  NULL COMMENT '上游 traceId：让"消息里的链路"能接上 HTTP 链路',
    last_error   VARCHAR(255) NULL COMMENT '最近一次失败原因',
    create_time  DATETIME(3)  NOT NULL COMMENT '创建时间',
    update_time  DATETIME(3)  NOT NULL COMMENT '更新时间',
    deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除（约定不使用：见文件头）',
    PRIMARY KEY (id),
    -- ★ 幂等的真正防线：同一个 (topic, msg_key) 只能插入一次，重复消费会撞唯一键
    UNIQUE KEY uk_msg_consume (topic, msg_key),
    KEY idx_msg_consume_status (status, update_time),
    KEY idx_msg_consume_tenant (tenant_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='消息消费幂等记录（至少一次投递下的去重依据）';
