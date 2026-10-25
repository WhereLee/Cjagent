package com.wherelee.cabinet.locker.api;

import com.wherelee.cabinet.locker.api.SizeType;

public interface SlotAllocator {

    AllocatedSlot allocate(Long cabinetId, SizeType required, Long orderId);

    String strategy();

    record AllocatedSlot(Long slotId, SizeType actualSize, int triedSlots) {
    }
}
