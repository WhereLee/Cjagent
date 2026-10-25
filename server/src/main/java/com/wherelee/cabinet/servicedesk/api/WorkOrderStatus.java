package com.wherelee.cabinet.servicedesk.api;

import java.util.Locale;
import java.util.Set;

public enum WorkOrderStatus {

    PENDING,
    CLAIMED,
    RESOLVED,
    AUTO_CLOSED,
    CANCELLED;

    private static final Set<WorkOrderStatus> OPEN = Set.of(PENDING, CLAIMED);

    public boolean isOpen() {
        return OPEN.contains(this);
    }

    public boolean closes() {
        return this == RESOLVED || this == AUTO_CLOSED || this == CANCELLED;
    }

    public static WorkOrderStatus of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
