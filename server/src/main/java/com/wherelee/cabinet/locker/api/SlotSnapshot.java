package com.wherelee.cabinet.locker.api;

import java.time.LocalDateTime;

/** 格口的只读快照：订单/工单/运营台看格口时只需要这些字段。 */
public record SlotSnapshot(Long id,
                           Long cabinetId,
                           String slotNo,
                           String sizeType,
                           String status,
                           String anomaly,
                           Long currentOrderId,
                           LocalDateTime doorOpenAt,
                           LocalDateTime doorClosedAt) {

    /** 与实体同一判据：开门时刻非空即视为门开着（关门时该列被清空）。 */
    public boolean doorOpen() {
        return doorOpenAt != null;
    }
}
