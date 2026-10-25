package com.wherelee.cabinet.locker.api;

import java.time.LocalDateTime;

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

    public boolean doorOpen() {
        return doorOpenAt != null;
    }
}
