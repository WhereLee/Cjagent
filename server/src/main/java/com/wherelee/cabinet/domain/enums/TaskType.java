package com.wherelee.cabinet.domain.enums;

import java.util.Locale;

public enum TaskType {

    SLOT_RELEASE,
    DOOR_NOT_CLOSED,
    OVERDUE_PICKUP,
    DEPOSIT_REFUND,
    COMMAND_RESCAN,
    COMMAND_SWEEP,
    FREE_SET_SYNC,
    LEDGER_RECONCILE,
    WORK_ORDER_RESPONSE_SLA,
    WORK_ORDER_RESOLVE_SLA;

    public int maxAttempts() {
        return switch (this) {
            case DEPOSIT_REFUND -> 8;
            case COMMAND_RESCAN -> 6;
            case FREE_SET_SYNC, LEDGER_RECONCILE, COMMAND_SWEEP -> 3;
            default -> 5;
        };
    }

    public boolean recurring() {
        return this == OVERDUE_PICKUP || this == FREE_SET_SYNC || this == LEDGER_RECONCILE
                || this == COMMAND_SWEEP || this == COMMAND_RESCAN;
    }

    public static TaskType of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
