package com.wherelee.cabinet.device.api;

public enum CommandAction {

    OPEN,
    PREPARE,
    OPEN_TEMP,
    CLOSE_VERIFY,
    FORCE_OPEN;

    public boolean isRetrieveLike() {
        return this == OPEN_TEMP || this == FORCE_OPEN;
    }

    public static CommandAction of(String value) {
        return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
