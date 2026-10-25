package com.wherelee.cabinet.funds.api;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

public enum DepositStatus {

    HELD,
    REFUNDING,
    REFUNDED,
    FAILED;

    private static final Set<DepositStatus> TERMINAL = EnumSet.of(REFUNDED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public static DepositStatus of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
