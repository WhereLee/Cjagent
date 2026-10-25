package com.wherelee.cabinet.domain.enums;

import java.util.Locale;

public enum CloseReason {

    MANUAL,
    FACT_RECOVERED,
    USER_CONFIRMED,
    MERGED,
    CANCELLED;

    public static CloseReason of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
