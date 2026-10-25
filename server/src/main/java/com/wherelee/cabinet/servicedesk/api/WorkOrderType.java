package com.wherelee.cabinet.servicedesk.api;

import java.util.Locale;

public enum WorkOrderType {

    DOOR_NOT_CLOSED,
    ITEM_LEFT,
    DEVICE_FAULT,
    LONG_RUNNING;

    public static WorkOrderType of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
