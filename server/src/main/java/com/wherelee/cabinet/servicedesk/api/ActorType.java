package com.wherelee.cabinet.servicedesk.api;

import java.util.Locale;

public enum ActorType {

    USER,
    AI,
    OPERATOR,
    SYSTEM;

    public static ActorType of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
