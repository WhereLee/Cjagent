package com.wherelee.cabinet.locker.api;

import java.util.Optional;

public interface SlotSwapPort {

    Optional<SlotSnapshot> swapIn(Long cabinetId, SizeType required, Long orderId, Long oldSlotId);
}
