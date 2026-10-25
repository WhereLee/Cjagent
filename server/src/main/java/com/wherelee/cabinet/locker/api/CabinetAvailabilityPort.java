package com.wherelee.cabinet.locker.api;

import java.util.List;

public interface CabinetAvailabilityPort {

    record ClosableView(String cabinetNo, String slotNo, String sizeType) {
    }

    record SizeFree(String sizeType, long free) {
    }

    record Availability(String cabinetNo, List<SizeFree> sizes) {
    }

    List<ClosableView> closableSlots(String cabinetNo);

    String confirmClosedByAnother(String cabinetNo, String slotNo, Long helperCustomerId);

    Availability of(String cabinetNo);
}
