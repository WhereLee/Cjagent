package com.wherelee.cabinet.locker.api;

public interface SlotPort {

    Sensing sense(Long cabinetId, Long slotId);

    boolean fresh(Sensing sensing);

    void afterDoorOpened(Long slotId, Long customerId);

    boolean afterDoorClosed(Long slotId, Long customerId);

    void tryAutoRecover(Long slotId, Sensing sensing);

    void markContentLeft(Long slotId, String reason);

    void markContentUnverified(Long slotId, String reason);

    void markSensorConflict(Long slotId, String reason);

    void markDoorOpenPastTolerance(Long slotId, long openMinutes);

    void recordRetrievalEvidence(Long slotId, String note);

    void recordManualRecovery(Long slotId, String note);

    void markForcedOpen(Long slotId, Long adminId, String reason);

    boolean reserve(Long slotId, Long orderId);

    boolean occupy(Long slotId, Long orderId);

    boolean releaseReserved(Long slotId, Long orderId);

    boolean releaseOccupied(Long slotId, Long orderId);

    void markFaulty(Long slotId);

    void markSlotFault(Long slotId);

    void disableCabinet(Long cabinetId);

    boolean clearAnomaly(Long slotId);
}
