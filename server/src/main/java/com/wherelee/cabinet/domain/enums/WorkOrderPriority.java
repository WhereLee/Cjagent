package com.wherelee.cabinet.domain.enums;

import java.util.Locale;

/** 工单优先级：由影响范围和紧急度共同决定，不再用一个 urgent 布尔表达。 */
public enum WorkOrderPriority {

    P1,
    P2,
    P3,
    P4;

    public static WorkOrderPriority of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
