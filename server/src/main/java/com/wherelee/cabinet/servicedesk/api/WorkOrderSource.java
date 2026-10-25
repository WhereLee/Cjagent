package com.wherelee.cabinet.servicedesk.api;

import java.util.Locale;

public enum WorkOrderSource {

    DEVICE,
    USER,
    AI,
    SYSTEM;

    public static WorkOrderSource of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
