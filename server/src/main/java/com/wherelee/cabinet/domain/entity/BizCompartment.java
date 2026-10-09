package com.wherelee.cabinet.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.wherelee.cabinet.domain.enums.CompartmentAnomaly;
import com.wherelee.cabinet.domain.enums.Presence;
import com.wherelee.cabinet.domain.enums.SizeType;
import com.wherelee.cabinet.domain.enums.SlotStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 格口。
 *
 * <p>一个格口上有三个<b>互相正交</b>的事实，分开存、不许挤进同一个字段：
 * <ul>
 *   <li>{@code status} + {@code currentOrderId}：能不能卖、被哪张单占着；</li>
 *   <li>{@code doorXxx}：门开着没、开了多久、谁开关的；</li>
 *   <li>{@code presence} + {@code anomaly}：柜内有没有东西、因此算不算一类异常。</li>
 * </ul>
 *
 * <p>为什么不把“门开着”做成 {@link SlotStatus} 的一个枚举值：那样就丢掉了
 * “门开着但里面已经有件”（投件后走了）与“门开着且里面没件”（开完没放）的区分，
 * 而这两种现场的处置完全不同（docs/门态与物品争议设计.md §4）。
 * 这是“门关了不等于柜内是空的”这句话在字段层面上的落实。
 *
 * <p>{@code currentOrderId} 与 {@code status} 是同一事实的两种记法，因此必须一致：
 * 空闲时 currentOrderId 为空、占用时非空。<b>不变量由服务层维护，由测试断言</b>
 * （{@code SlotOrderInvariantsTest}）。
 *
 * <p>{@code version} 走 MyBatis-Plus 乐观锁；注意它要求
 * {@code OptimisticLockerInnerInterceptor} 已注册，否则这就是个不会自己动的普通列。
 */
@Getter
@Setter
@TableName("biz_compartment")
public class BizCompartment extends BaseEntity {

    private Long cabinetId;
    private String slotNo;
    private SizeType sizeType;
    private SlotStatus status;
    private Long currentOrderId;

    /** 门打开的起始时刻；NULL = 当前是关上的。计费起点与提醒时长都据它。 */
    private LocalDateTime doorOpenAt;

    /** 这次是谁开的门（客户 ID）。 */
    private Long doorOpenedBy;

    /** 最近一次关门时刻；门开着时为 NULL。 */
    private LocalDateTime doorClosedAt;

    /** 谁关的门：可能本人，也可能是下一位顺手代关的客户（纠纷还原用）。 */
    private Long doorClosedBy;

    /** 最近一次柜内物检结论（三值，UNKNOWN 不算凭据）。 */
    private Presence presence;

    /**
     * 物检时刻。<b>参与判定前必须先查这个时间</b>：设备“看了一眼”的时刻不等于“现在”，
     * 拿三分钟前的结论去放行一个格口，就是把“当时是空的”当成“现在也是空的”。
     */
    private LocalDateTime presenceCheckedAt;

    /** 异常原因（门/物四类）；NULL = 无异常。硬件故障与人工停用走 {@code status}。 */
    private CompartmentAnomaly anomaly;

    /** 异常判定时刻（台账排序与停留时长统计）。 */
    private LocalDateTime anomalyAt;

    /** 异常说明：业务运维到场要看的是这句，不是枚举名。 */
    private String anomalyReason;

    @Version
    private Integer version;

    /** 门此刻是否开着。 */
    public boolean doorOpen() {
        return doorOpenAt != null;
    }

    /** 已开了多久（没开返回 0）。巡检提醒与“开了整天”的取数都看它。 */
    public long doorOpenMinutes() {
        return doorOpenAt == null ? 0L : Duration.between(doorOpenAt, LocalDateTime.now()).toMinutes();
    }

    /**
     * 这条物检结论能不能当凭据用。
     *
     * <p>超过 {@code maxAgeMinutes} 的结论一律不算：宁可退回去标
     * {@link CompartmentAnomaly#CONTENT_UNVERIFIED}，也不能拿陈年结论把格子卖出去。
     */
    public boolean presenceStillValid(int maxAgeMinutes) {
        return presenceCheckedAt != null
                && Duration.between(presenceCheckedAt, LocalDateTime.now()).toMinutes() <= maxAgeMinutes;
    }

    /** 是否明确确认无物（结论有效且为 ABSENT）。 */
    public boolean confirmedEmpty(int maxAgeMinutes) {
        return presenceStillValid(maxAgeMinutes) && presence != null && presence.confirmsEmpty();
    }

    /**
     * 当前能不能被新订单分配。<b>与候选查询同一套判据</b>，
     * 否则会出现“Java 说可用、SQL 没查出来”或反过来（四条分配策略已经证实过这种不一致有多可怕）。
     */
    public boolean assignable() {
        return status != null && status.assignable() && !doorOpen() && anomaly == null;
    }
}
