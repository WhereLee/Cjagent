package com.wherelee.cabinet.notify.api;

public enum NoticeChannel {

    SYSTEM_MESSAGE,
    PHONE_CALL,
    QUEUE;

    public static NoticeChannel ofNullable(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (NoticeChannel value : values()) {
            if (value.name().equals(raw)) {
                return value;
            }
        }
        return null;
    }
}
