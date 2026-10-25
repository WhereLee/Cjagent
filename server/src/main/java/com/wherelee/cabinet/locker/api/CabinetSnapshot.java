package com.wherelee.cabinet.locker.api;

public record CabinetSnapshot(Long id,
                              Long tenantId,
                              Long siteId,
                              String cabinetNo,
                              String name,
                              String cabinetStatus,
                              String onlineState) {
}
