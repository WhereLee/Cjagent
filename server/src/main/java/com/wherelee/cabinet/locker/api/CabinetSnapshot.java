package com.wherelee.cabinet.locker.api;

/** 柜机的只读快照。 */
public record CabinetSnapshot(Long id,
                              Long siteId,
                              String cabinetNo,
                              String name,
                              String cabinetStatus,
                              String onlineState) {
}
