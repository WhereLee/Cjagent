package com.wherelee.cabinet.device.api;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public enum CommandState {

    PENDING,
    SENT,
    ACKED,
    SUCCEEDED,
    FAILED,
    TIMEOUT;

    private static final Set<CommandState> TERMINAL = EnumSet.of(SUCCEEDED);

    private static final Map<CommandState, Set<CommandState>> TRANSITIONS = new EnumMap<>(CommandState.class);

    static {
        TRANSITIONS.put(PENDING, EnumSet.of(SENT, FAILED, TIMEOUT));
        TRANSITIONS.put(SENT, EnumSet.of(ACKED, SUCCEEDED, FAILED, TIMEOUT));
        TRANSITIONS.put(ACKED, EnumSet.of(SUCCEEDED, FAILED, TIMEOUT));
        TRANSITIONS.put(FAILED, EnumSet.of(SENT, TIMEOUT));
        TRANSITIONS.put(TIMEOUT, EnumSet.of(SENT, SUCCEEDED, FAILED));
        TRANSITIONS.put(SUCCEEDED, EnumSet.noneOf(CommandState.class));

        for (CommandState state : values()) {
            TRANSITIONS.computeIfAbsent(state, k -> EnumSet.noneOf(CommandState.class));
        }
        TERMINAL.forEach(terminal -> {
            if (!TRANSITIONS.get(terminal).isEmpty()) {
                throw new IllegalStateException("指令终态不允许有出边: " + terminal);
            }
        });
    }

    public boolean canTransitTo(CommandState target) {
        return TRANSITIONS.getOrDefault(this, EnumSet.noneOf(CommandState.class)).contains(target);
    }

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public boolean isRetriable() {
        return this == FAILED || this == TIMEOUT;
    }

    public static CommandState of(String value) {
        return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
