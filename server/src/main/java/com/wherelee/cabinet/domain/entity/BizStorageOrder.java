package com.wherelee.cabinet.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.enums.OrderCloseReason;
import com.wherelee.cabinet.domain.enums.OrderStatus;
import com.wherelee.cabinet.locker.api.SizeType;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("biz_storage_order")
public class BizStorageOrder extends BaseEntity {

    private String orderNo;
    private Long siteId;
    private Long cabinetId;
    private Long slotId;
    private SizeType sizeType;
    private Long customerId;

    private OrderStatus status;

    @TableField(value = "active_flag", updateStrategy = FieldStrategy.ALWAYS)
    private Integer activeFlag;

    private String voucherCode;

    @TableField("pricing_snapshot")
    private String pricingSnapshot;

    private LocalDateTime startedAt;
    private LocalDateTime expectedFinishAt;

    private LocalDateTime toleranceUntil;

    private OrderCloseReason closeReason;

    private Long remoteClosePoints;

    private com.wherelee.cabinet.domain.enums.DisputeState disputeState;

    private LocalDateTime disputeStartedAt;

    private Integer aiReviewCount;

    private LocalDateTime leftoverReportedAt;

    private LocalDateTime finishedAt;
    private Integer tempOpenCount;

    private Integer voucherWrongCount;

    private java.time.LocalDateTime voucherLockedAt;

    private Long depositPoints;
    private Long frozenPoints;

    private Long prepayPoints;
    private Long settledPoints;
    private Long arrearsPoints;

    private String lastError;

    @Version
    private Integer version;

    public boolean inDispute() {
        return disputeStartedAt != null;
    }

    public void transitTo(OrderStatus target) {
        if (status == null) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "订单状态为空，数据异常: " + orderNo);
        }
        if (!status.canTransitTo(target)) {
            throw new BizException(ResultCode.BIZ_ERROR,
                    "订单 " + orderNo + " 不允许从 " + status + " 迁移到 " + target);
        }
        this.status = target;
        this.activeFlag = target.activeFlag();
    }

    public void initStatus() {
        this.status = OrderStatus.INIT;
        this.activeFlag = OrderStatus.INIT.activeFlag();
    }
}
