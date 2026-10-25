package com.wherelee.cabinet.locker.api;

import java.util.List;
import java.util.Locale;

public enum SizeType {

    LARGE(1),
    MEDIUM(2),
    SMALL(3);

    private final int rank;

    SizeType(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    public boolean fits(SizeType required) {
        return this.rank <= required.rank;
    }

    public static List<SizeType> acceptanceOrder(SizeType required) {
        return List.of(required);
    }

    public static SizeType of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
