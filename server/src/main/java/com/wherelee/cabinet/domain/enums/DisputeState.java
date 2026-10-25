package com.wherelee.cabinet.domain.enums;

import java.util.Locale;

public enum DisputeState {

    ITEM_DISPUTED,
    AI_REVIEWED,
    HUMAN_REVIEW;

    public static DisputeState of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
