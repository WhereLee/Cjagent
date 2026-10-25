package com.wherelee.cabinet.servicedesk.api;

import java.util.Locale;

public enum WorkOrderPriority {

    P1,
    P2,
    P3,
    P4;

    public static WorkOrderPriority of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
