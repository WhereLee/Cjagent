package com.wherelee.cabinet.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.enums.OrderCloseReason;
import com.wherelee.cabinet.domain.enums.OrderStatus;
import com.wherelee.cabinet.domain.enums.SizeType;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 寄存单。
 *
 * <p><b>状态只能通过 {@link #transitTo} 改，不允许业务代码 setStatus。</b>理由不是洁癖：
 * {@code activeFlag} 必须与 status 同步，否则唯一索引 {@code uk_order_active_slot} 的防超卖
 * 语义就废了——而这个失效在功能测试里完全看不出来，只会在并发下变成"同一个格口两单活动"。
 * 把两者绑定在一个方法里，是让错误写法在结构上不存在。
 *
 * <p>{@code pricingSnapshot} 对应 MySQL 的 JSON 列，本刀先按字符串读写（MySQL 仍会校验 JSON 合法性），
 * 第 12 刀接计费时再换 MP 的 JacksonTypeHandler 做对象化——现在换要动 autoResultMap，收益不到。
 */
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

    /**
     * 活动=1，终态=NULL；由 {@link #transitTo} 维护，不要直接 set。
     *
     * <p>{@code updateStrategy = ALWAYS} 不是冗余配置：MyBatis-Plus 默认**不写 null 字段**，
     * 于是“把 active_flag 置 NULL 以释放唯一占位”这个动作会被静默丢掉——
     * 集成测试实测到：单子进了终态，格口却永久不可再用（比超卖更隐蔽）。
     */
    @TableField(value = "active_flag", updateStrategy = FieldStrategy.ALWAYS)
    private Integer activeFlag;

    private String voucherCode;

    @TableField("pricing_snapshot")
    private String pricingSnapshot;

    private Integer estimateMinutes;
    private LocalDateTime startedAt;
    private LocalDateTime expectedFinishAt;

    /**
     * 开门容错到期时刻（第 13B 刀，docs/门态与物品争议设计.md §2）。
     *
     * <p>它在单上而不是现算“开门时刻 + 配置值”：运营中途把容错从 5 分钟改成了 10 分钟，
     * 不得追溯改变进行中那张单该从几点开始计。<b>非空也是“这单曾经开过门”的凭据</b>：
     * 超时自动释放只释“从未开过门”的单，开过门的单件可能已在柜内，不能被默默取消。
     */
    private LocalDateTime toleranceUntil;

    /** 结束原因：不同结束方式<b>价格不同</b>且格口后续处置不同，不能只记“已关闭”。 */
    private OrderCloseReason closeReason;

    /** 未关门加收的点数（= 该格口单价 × remote-close-hours），冷数据在单上以便账单直读。 */
    private Long remoteClosePoints;

    private LocalDateTime finishedAt;
    private Integer tempOpenCount;

    private Long depositPoints;
    private Long frozenPoints;
    private Long settledPoints;
    private Long arrearsPoints;

    private String lastError;

    @Version
    private Integer version;

    /**
     * 状态迁移的唯一入口。非法迁移抛业务异常（10000 族 → HTTP 200 + code，
     * 因为它是"你的请求在当前状态下不合法"，不是鉴权也不是服务端故障）。
     */
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

    /** 给新建订单用的初始态（同样走迁移语义，避免 INIT 与 activeFlag 不一致）。 */
    public void initStatus() {
        this.status = OrderStatus.INIT;
        this.activeFlag = OrderStatus.INIT.activeFlag();
    }
}
