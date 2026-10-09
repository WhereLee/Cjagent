package com.wherelee.cabinet.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wherelee.cabinet.domain.enums.DepositStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 押金（每单一条，DDL 上 {@code uk_deposit_order}）。
 *
 * <p>押金的语义是<b>义务</b>不是收入：所以它必须有"退到哪、什么时候退的"的完整链路
 * （{@code heldTxnId} / {@code refundTxnId} 两条流水），而不是只在账户里减一笔。
 * 少了这两条外键，"未退清单"就查不出来——那正是不变量 4 要盯的悬挂。
 */
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

    /** 失败原因要能被清空（重试成功后不留旧错误），所以 ALWAYS——MP 默认不写 null。 */
    @TableField(value = "fail_reason", updateStrategy = FieldStrategy.ALWAYS)
    private String failReason;
}
