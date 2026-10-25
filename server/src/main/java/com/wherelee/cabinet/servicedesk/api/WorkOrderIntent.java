package com.wherelee.cabinet.servicedesk.api;

import java.util.Locale;

public enum WorkOrderIntent {

    DOOR_NOT_OPEN,
    DOOR_NOT_CLOSE,
    ITEM_MISSING,
    ITEM_LEFT,
    DEVICE_OFFLINE,
    BILLING_DISPUTE,
    PICKUP_CODE,
    OTHER;

    public static WorkOrderIntent of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
