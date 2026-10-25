package com.wherelee.cabinet.device.api;

import java.util.Locale;

public enum ReportEvent {

    DOOR_OPENED,
    DOOR_CLOSED,
    HEARTBEAT,
    FAULT,
    STATE_SNAPSHOT;

    public static ReportEvent of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
