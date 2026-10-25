package com.wherelee.cabinet.domain.enums;

import java.util.Locale;

public enum OrderCloseReason {

    NORMAL,
    ABANDONED,
    REMOTE,
    DISPUTE_WAIVED;

    public boolean chargesPenalty() {
        return this == REMOTE;
    }

    public boolean leavesAnomaly() {
        return this == ABANDONED;
    }

    public static OrderCloseReason of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
