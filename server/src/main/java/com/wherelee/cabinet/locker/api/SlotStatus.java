package com.wherelee.cabinet.locker.api;

public enum SlotStatus {

    FREE,
    RESERVED,
    OCCUPIED,
    FAULT,
    MAINTENANCE;

    public boolean assignable() {
        return this == FREE;
    }
}
