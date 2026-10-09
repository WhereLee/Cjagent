package com.wherelee.cabinet.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wherelee.cabinet.domain.enums.PayTxnStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 支付通道流水（本地账务的<b>对手方</b>）。
 *
 * <p>它和点数流水（{@link BizPointTxn}）是两本账、两个真相源：这本记"通道收到钱没有"，
 * 那本记"我们给用户加点数没有"。对账就是在这两本之间比差异（见 ReconcileService），
 * <b>把它们合成一本就等于放弃了唯一能发现资损的手段</b>。
 *
 * <p>{@code outTradeNo} 由服务端生成并且是唯一键：重试、回调、查单都必须带同一个值，
 * 否则一次网络抖动就会变成两笔收款。
 */
@Getter
@Setter
@TableName("biz_pay_txn")
public class BizPayTxn extends BaseEntity {

    private Long customerId;

    /** 商户订单号，同时是点数流水的幂等号（{@code biz_point_txn.biz_no}）。 */
    private String outTradeNo;

    /** 通道标识（MOCK / ALIPAY / WECHAT）。存字符串不存枚举：可用通道是基础设施的事，不该钉进 domain。 */
    private String channel;

    private Long points;

    /** 应收金额（分）。1 点 = 1 分（S-01），整型，禁止浮点。 */
    private Long amountFen;

    private PayTxnStatus status;

    /** 通道侧交易号，与通道账单核对用。 */
    private String tradeNo;

    private LocalDateTime paidAt;
}
