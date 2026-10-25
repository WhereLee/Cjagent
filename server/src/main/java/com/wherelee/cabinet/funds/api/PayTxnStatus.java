package com.wherelee.cabinet.funds.api;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

public enum PayTxnStatus {

    CREATED,
    PAID,
    FAILED,
    REFUNDED;

    private static final Set<PayTxnStatus> TERMINAL = EnumSet.of(PAID, FAILED, REFUNDED);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public static PayTxnStatus of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
