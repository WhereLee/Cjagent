package com.wherelee.cabinet.locker.api;

public interface SlotPoolPort {

    Popped tryPop(Long cabinetId, SizeType required);

    void addFree(Long cabinetId, SizeType sizeType, Long slotId);

    void syncFreeSet(Long cabinetId, SizeType size);

    void syncAllFreeSets(Long cabinetId);

    record Popped(SizeType size, Long slotId) {
    }
}
