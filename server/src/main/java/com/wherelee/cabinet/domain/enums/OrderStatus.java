package com.wherelee.cabinet.domain.enums;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum OrderStatus {

    INIT,
    RESERVED,
    OPENING,
    STORED,
    ACTIVE,
    TEMP_OPEN,
    SETTLING,
    CLOSED,
    CANCELLED,
    ABNORMAL,
    EXPIRED;

    private static final Set<OrderStatus> TERMINAL = EnumSet.of(CLOSED, CANCELLED);

    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = new EnumMap<>(OrderStatus.class);

    static {
        TRANSITIONS.put(INIT, EnumSet.of(RESERVED, CANCELLED, ABNORMAL));
        TRANSITIONS.put(RESERVED, EnumSet.of(OPENING, CANCELLED, ABNORMAL));
        TRANSITIONS.put(OPENING, EnumSet.of(STORED, ACTIVE, RESERVED, CANCELLED, ABNORMAL));
        TRANSITIONS.put(STORED, EnumSet.of(ACTIVE, CANCELLED, ABNORMAL));
        TRANSITIONS.put(ACTIVE, EnumSet.of(TEMP_OPEN, SETTLING, EXPIRED, ABNORMAL));
        TRANSITIONS.put(TEMP_OPEN, EnumSet.of(ACTIVE, SETTLING, EXPIRED, ABNORMAL));
        TRANSITIONS.put(SETTLING, EnumSet.of(CLOSED, ABNORMAL));
        TRANSITIONS.put(EXPIRED, EnumSet.of(SETTLING, ABNORMAL));
        TRANSITIONS.put(ABNORMAL, EnumSet.of(ACTIVE, CANCELLED, CLOSED));
        TRANSITIONS.put(CLOSED, EnumSet.noneOf(OrderStatus.class));
        TRANSITIONS.put(CANCELLED, EnumSet.noneOf(OrderStatus.class));

        for (OrderStatus status : values()) {
            TRANSITIONS.computeIfAbsent(status, k -> EnumSet.noneOf(OrderStatus.class));
        }
        TERMINAL.forEach(terminal -> {
            if (!TRANSITIONS.get(terminal).isEmpty()) {
                throw new IllegalStateException("终态不允许有出边: " + terminal);
            }
        });
    }

    public boolean canTransitTo(OrderStatus target) {
        return TRANSITIONS.getOrDefault(this, EnumSet.noneOf(OrderStatus.class)).contains(target);
    }

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public Integer activeFlag() {
        return isTerminal() ? null : 1;
    }
}
