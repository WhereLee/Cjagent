package com.wherelee.cabinet.servicedesk.api;

import java.util.Locale;

public enum WorkOrderImpact {

    SINGLE,
    CABINET,
    ACCOUNT;

    public static WorkOrderImpact of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
