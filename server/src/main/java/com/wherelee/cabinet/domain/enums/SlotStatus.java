package com.wherelee.cabinet.domain.enums;

/**
 * 格口物理状态。
 *
 * <p>与寄存单状态是两件事：格口讲"里面有没有东西/能不能用"，订单讲"业务走到哪一步"。
 * 两者的耦合用不变量守住（见 docs/储物柜业务规划.md §4）：
 * {@code OCCUPIED ⟺ 存在活动单}，{@code FREE ⟺ 不存在活动单}。
 *
 * <p>{@link #FAULT} 与 {@link #MAINTENANCE} 都不可被分配，但含义不同：
 * 前者是系统按连续失败自动摘除（要能自动恢复），后者是人工停用（不会被自动放回来）。
 * 混成一个值，就会出现"运维停用的格口被自动恢复任务悄悄放开"这种事故。
 */
public enum SlotStatus {

    /** 空闲，可分配 */
    FREE,
    /** 已被预占（有活动单但件还没进去），带 TTL，超时自动回 FREE */
    RESERVED,
    /** 占用中（里面有件） */
    OCCUPIED,
    /** 故障：连续开锁失败/门磁异常等，系统自动摘出可分配池 */
    FAULT,
    /** 人工维护停用 */
    MAINTENANCE;

    /** 是否可被新订单分配。 */
    public boolean assignable() {
        return this == FREE;
    }
}
