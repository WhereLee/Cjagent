package com.wherelee.cabinet.storage.api;

public record OrderSnapshot(Long id,
                            String orderNo,
                            Long customerId,
                            Long cabinetId,
                            Long slotId,
                            String status,
                            String sizeType) {
}
