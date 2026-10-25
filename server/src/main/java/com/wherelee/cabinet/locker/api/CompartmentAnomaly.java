package com.wherelee.cabinet.locker.api;

import java.util.Locale;

public enum CompartmentAnomaly {

    DOOR_OPEN,
    CONTENT_LEFT,
    CONTENT_UNVERIFIED,
    SENSOR_CONFLICT;

    public boolean keepsBilling() {
        return this == DOOR_OPEN || this == SENSOR_CONFLICT;
    }

    public boolean autoRecoverable() {
        return this == DOOR_OPEN;
    }

    public static CompartmentAnomaly of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
