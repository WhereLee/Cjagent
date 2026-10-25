package com.wherelee.cabinet.domain.enums;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

public enum TaskStatus {

    PENDING,
    RUNNING,
    DONE,
    FAILED,
    DEAD;

    private static final Set<TaskStatus> TERMINAL = EnumSet.of(DONE, DEAD);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public static TaskStatus of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
