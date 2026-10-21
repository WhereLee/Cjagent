package com.wherelee.cabinet.device.api;

/**
 * 设备故障台账端口。
 *
 * <p>格口状态机在门态变化、人工清柜、强制开门时都要留一条台账；台账归设备，
 * 所以只暴露“写一条”这个动作，不把故障表交出去。
 */
public interface FaultPort {

    void record(Long cabinetId, Long slotId, FaultType.Action action, int failCount, String reason);
}
