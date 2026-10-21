package com.wherelee.cabinet.locker.api;

import java.time.LocalDateTime;

/**
 * 格口的只读快照：订单/工单/运营台看格口时只需要这些字段。
 *
 * <p>它带 tenantId 与 doorOpenedBy 不是“多给几个字段”：工单要在没有活动单时
 * 靠开门人回退找物主，而快照一旦缺这个事实，调用方就只能回去读实体。
 */
public record SlotSnapshot(Long id,
                           Long tenantId,
                           Long cabinetId,
                           String slotNo,
                           String sizeType,
                           String status,
                           String anomaly,
                           Long currentOrderId,
                           Long doorOpenedBy,
                           String presence,
                           LocalDateTime doorOpenAt,
                           LocalDateTime doorClosedAt) {

    /** 与实体同一判据：开门时刻非空即视为门开着（关门时该列被清空）。 */
    public boolean doorOpen() {
        return doorOpenAt != null;
    }
}
