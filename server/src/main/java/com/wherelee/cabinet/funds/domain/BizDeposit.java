package com.wherelee.cabinet.funds.domain;

import com.wherelee.cabinet.domain.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wherelee.cabinet.funds.api.DepositStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("biz_deposit")
public class BizDeposit extends BaseEntity {

    private Long orderId;

    private Long customerId;

    private Long points;

    private DepositStatus status;

    private Long heldTxnId;

    private Long refundTxnId;

    private LocalDateTime heldAt;

    private LocalDateTime refundedAt;

    @TableField(value = "fail_reason", updateStrategy = FieldStrategy.ALWAYS)
    private String failReason;
}
