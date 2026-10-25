package com.wherelee.cabinet.device.api;

public interface FaultPort {

    void record(Long cabinetId, Long slotId, FaultType.Action action, int failCount, String reason);
}
