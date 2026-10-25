package com.wherelee.cabinet.servicedesk.api;

public enum SlaPauseReason {

    WAITING_CUSTOMER;

    public static SlaPauseReason ofNullable(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (SlaPauseReason value : values()) {
            if (value.name().equals(raw)) {
                return value;
            }
        }
        return null;
    }
}
