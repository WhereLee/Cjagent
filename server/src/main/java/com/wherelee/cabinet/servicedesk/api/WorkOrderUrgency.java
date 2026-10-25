package com.wherelee.cabinet.servicedesk.api;

import java.util.Locale;

public enum WorkOrderUrgency {

    HIGH,
    NORMAL,
    LOW;

    public static WorkOrderUrgency of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
