package com.wherelee.cabinet.device.api;

/**
 * 一次指令下发的结果。
 *
 * @param businessSuccess 设备是否<b>明确</b>成功；已下发无回执为 false（不能当成功）
 * @param stale           回执是重复/乱序的旧事件：已入库留证，但不得改变业务状态
 * @param faultType       本次触发的故障类型（null 表示无故障）
 */
public record CommandOutcome(Long commandId,
                             CommandState state,
                             boolean businessSuccess,
                             boolean stale,
                             FaultType faultType,
                             String message) {
}
