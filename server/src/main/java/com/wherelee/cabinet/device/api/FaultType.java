package com.wherelee.cabinet.device.api;

import java.util.Locale;

public enum FaultType {

    OPEN_FAILED,
    DOOR_NOT_CLOSED,
    NO_REPORT,
    TARGET_MISMATCH,
    OFFLINE,
    CONSECUTIVE_FAIL;

    public static FaultType of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    public enum Action {
        MARK_UNAVAILABLE,
        MARK_ONLINE,
        ALARM,
        RECOVER
    }
}
