package com.wherelee.cabinet.domain.enums;

import java.util.Locale;

/** 紧急度：影响范围之外的第二维，用来算优先级和 SLA。 */
public enum WorkOrderUrgency {

    HIGH,
    NORMAL,
    LOW;

    public static WorkOrderUrgency of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
