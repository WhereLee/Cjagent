package com.wherelee.cabinet.storage.api;

public record StorageOrderView(String orderNo,
                               String cabinetNo,
                               String slotNo,
                               String allocatedSize,
                               String status,
                               int triedSlots,
                               String strategy,
                               String message) {

    public StorageOrderView withMessage(String note) {
        return new StorageOrderView(orderNo, cabinetNo, slotNo, allocatedSize, status,
                triedSlots, strategy, note);
    }
}
