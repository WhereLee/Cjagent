package com.wherelee.cabinet.storage.api;

public record VoucherView(String orderNo,
                          String cabinetNo,
                          String slotNo,
                          String sizeType,
                          String status,
                          String voucherCode,
                          boolean locked) {
}
