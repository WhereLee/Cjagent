package com.wherelee.cabinet.locker.api;

/**
 * 格口状态端口：门态、物检、异常、占用与释放都从这里走。
 *
 * <p>它把“谁能改格口”收成一条口子。订单、工单、资金、设备都不再直接
 * update 格口表，也不自己判断“门开着算不算可售”。
 *
 * <p>为什么只收方法、不收实体：格口表的门态四列互相有约束（门开着、谁开关的、
 * 里面有没有东西、异常是哪一类），一旦把实体交出去，调用方就会开始自己拼状态。
 * 端口这边只收 id，回的是只读快照（{@link SlotSnapshot}），写的是有名字的动作。
 */
public interface SlotPort {

    /** 问一次设备：门关没关、里面有没有东西。结论同时落库。 */
    Sensing sense(Long cabinetId, Long slotId);

    /** 这条物检结论还能不能当凭据（时效见 {@code cabinet.sensor.presence-max-age-minutes}）。 */
    boolean fresh(Sensing sensing);

    /** 开门后：记录开门人与时刻，并做开门时的物检留证。 */
    void afterDoorOpened(Long slotId, Long customerId);

    /** 关门后：物检、异常判定、可售恢复。@return 是否真的把“开着”关成了“关上” */
    boolean afterDoorClosed(Long slotId, Long customerId);

    /** 门关了但异常仍在时，尝试按事实自动恢复。 */
    void tryAutoRecover(Long slotId, Sensing sensing);

    void markContentLeft(Long slotId, String reason);

    void markContentUnverified(Long slotId, String reason);

    void markSensorConflict(Long slotId, String reason);

    void markDoorOpenPastTolerance(Long slotId, long openMinutes);

    void recordRetrievalEvidence(Long slotId, String note);

    void recordManualRecovery(Long slotId, String note);

    void markForcedOpen(Long slotId, Long adminId, String reason);

    /** 分配前的预占（条件更新：只预占空闲格）。 */
    boolean reserve(Long slotId, Long orderId);

    /** 投件完成（条件更新：只推自己预占的格，RESERVED → OCCUPIED）。 */
    boolean occupy(Long slotId, Long orderId);

    /**
     * 取消/超时回滚：只放自己预占的格（RESERVED）。
     *
     * <p>与 {@link #releaseOccupied} 分开而不是写成一条 {@code status in (...)}：
     * 这两个状态对应两件不同的事（未投件取消 vs 已投件取走），混在一条里就丢掉了
     * “件在不在柜里”这个关键信息，出事故时无法从数据上判断发生过什么。
     */
    boolean releaseReserved(Long slotId, Long orderId);

    /** 取件后的释放：只放自己占用的格（OCCUPIED）。 */
    boolean releaseOccupied(Long slotId, Long orderId);

    void markFaulty(Long slotId);

    /** 清柜：解除异常。@return 是否真的清掉（重复点第二次为 false） */
    boolean clearAnomaly(Long slotId);
}
