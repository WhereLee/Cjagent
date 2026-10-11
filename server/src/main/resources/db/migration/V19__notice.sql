-- ============================================================================
-- V19：通知与"必须人工办"的任务（19D 第 2 条）
--
-- 你说"打电话提醒"。系统里没有短信/外呼通道，所以这里做的**不是假装打电话**，而是：
--   channel=PHONE_CALL —— 一条挂在工单上的待办："必须电话联系客户 138xxxx"，
--                          业务员打完后回填结果（done_at + 结论写进工单事件）；
--   channel=SYSTEM_MESSAGE —— 发给客户的站内消息，他自己打开小程序能看到（我们没有推送）。
-- 两者都存在这张表里，因为它们的形式都一样："某条异常，该被某人看到一次"。
--
-- `dedup_key` 上有唯一索引：一次升级只应生成一条。阶梯每轮巡检都会重进这段逻辑，
-- 没有这个键就会堆出几十条一模一样的"打电话"，队列立刻失去意义（这是本项目反复出现的形状：
-- 幂等不能靠"先查一下"，要落在索引上）。
-- ============================================================================

CREATE TABLE IF NOT EXISTS biz_notice (
    id              BIGINT        NOT NULL COMMENT '雪花 ID',
    tenant_id       BIGINT        NOT NULL,
    customer_id     BIGINT        NULL COMMENT '站内消息的收件人；PHONE_CALL 类可为空（挂在工单上）',
    work_order_id   BIGINT        NULL COMMENT '引起这条通知的工单',
    order_no        VARCHAR(150)  NULL,
    channel         VARCHAR(24)   NOT NULL COMMENT 'SYSTEM_MESSAGE 站内消息 / PHONE_CALL 需人工打电话',
    kind            VARCHAR(32)   NOT NULL COMMENT 'DOOR_NOT_CLOSED / ITEM_VALUABLE / LONG_RUNNING 等，用于去重与文案归类',
    dedup_key       VARCHAR(96)   NOT NULL COMMENT '去重键：同一异常同一档只生成一条（唯一索引兜住）',
    content         VARCHAR(500)  NOT NULL COMMENT '给人看的一句话，必须含编号与联系方式等现场信息',
    done_at         DATETIME(3)   NULL COMMENT '站内消息=已读时刻；PHONE_CALL=回填拨打结果的时刻',
    done_note       VARCHAR(300)  NULL COMMENT 'PHONE_CALL 的回填结论：联系上了/没接/约定多久处理',
    create_time     DATETIME(3)   NOT NULL DEFAULT NOW(3),
    update_time     DATETIME(3)   NOT NULL DEFAULT NOW(3) ON UPDATE NOW(3),
    deleted         TINYINT(1)    NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_notice_dedup (tenant_id, dedup_key),
    KEY idx_notice_customer (customer_id, done_at, create_time),
    KEY idx_notice_work_order (work_order_id, channel)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '通知与人工待办（站内消息、需打电话任务）';
