package com.wherelee.cabinet.servicedesk.api;

import java.util.Locale;

public enum WorkOrderKind {

    INCIDENT,
    REQUEST;

    public static WorkOrderKind of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
