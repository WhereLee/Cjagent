package com.wherelee.cabinet.servicedesk.api;

import java.util.Locale;

public enum WorkOrderCategory {

    DOOR_FAULT,
    DOOR_NOT_CLOSED,
    ITEM_DISPUTE,
    ITEM_LEFT,
    DEVICE_OFFLINE,
    BILLING_DISPUTE,
    PICKUP_CODE,
    UNSORTED;

    public static WorkOrderCategory of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
