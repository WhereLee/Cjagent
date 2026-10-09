-- ============================================================================
-- V9：支付通道流水（对账的对手方）
--
-- 为什么第 13 刀要建这张表：点数账本的"本地有入账"与"通道真收到钱"是**两件事**。
-- 没有对手方数据，"对账"就只能是 Σ流水 vs 余额的自证——那种对账查不出
-- "钱收了但没给人加点数"这类真正的资损。有了这张表，两个方向都能各出一条差异。
--
-- 通道现在是 Mock（S-11：真实支付宝/微信推到最后），但**表结构与语义按真实通道设计**：
-- out_trade_no 由服务端生成、状态由通道侧推进、入账幂等键与它一一对应。
-- 换真实通道时只换 PayChannel 的实现与回调入口，这张表和账务都不用改。
-- ============================================================================

CREATE TABLE IF NOT EXISTS biz_pay_txn
(
    id           BIGINT      NOT NULL COMMENT '雪花 ID',
    tenant_id    BIGINT      NOT NULL COMMENT '运营商 ID',
    customer_id  BIGINT      NOT NULL COMMENT '充值客户',
    -- 商户订单号：服务端生成，重试/回调都带同一个值；它同时是点数流水的幂等号
    out_trade_no VARCHAR(64) NOT NULL COMMENT '商户订单号（RC + 雪花），点数流水 biz_no 就是它',
    channel      VARCHAR(24) NOT NULL COMMENT '通道标识：MOCK / ALIPAY / WECHAT',
    points       BIGINT      NOT NULL COMMENT '本单购买点数',
    -- 1 点 = 1 分（S-01）：金额用整型分，任何浮点都不许进资金链路
    amount_fen   BIGINT      NOT NULL COMMENT '应收金额（分）',
    status       VARCHAR(16) NOT NULL DEFAULT 'CREATED' COMMENT 'CREATED / PAID / FAILED / REFUNDED',
    trade_no     VARCHAR(64) NULL COMMENT '通道侧交易号（Mock 用 MOCK+雪花），用于与通道账单核对',
    paid_at      DATETIME(3) NULL COMMENT '通道确认收款时间（服务端接收时刻，S-07）',
    create_time  DATETIME(3) NOT NULL COMMENT '创建时间',
    update_time  DATETIME(3) NOT NULL COMMENT '更新时间',
    deleted      TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    -- 一单一号：重复回调/重复下单在存储层就撞不上第二次
    UNIQUE KEY uk_pay_out_trade_no (out_trade_no),
    -- 对账按"通道侧已收款"取数，状态在前、时间在后才吃得下这个索引
    KEY idx_pay_status_time (status, create_time),
    KEY idx_pay_customer (customer_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='支付通道流水（本地账务的对手方，只增不改语义）';
