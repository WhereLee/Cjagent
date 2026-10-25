package com.wherelee.cabinet.locker.api;

import java.util.Locale;

public enum Presence {

    PRESENT,
    ABSENT,
    UNKNOWN;

    public boolean confirmsEmpty() {
        return this == ABSENT;
    }

    public static Presence of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
