package com.wherelee.cabinet.funds.domain;

import com.wherelee.cabinet.domain.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wherelee.cabinet.funds.api.PayTxnStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("biz_pay_txn")
public class BizPayTxn extends BaseEntity {

    private Long customerId;

    private String outTradeNo;

    private String channel;

    private Long points;

    private Long amountFen;

    private PayTxnStatus status;

    private String tradeNo;

    private LocalDateTime paidAt;
}
