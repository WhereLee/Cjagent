package com.wherelee.cabinet.funds.api;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

public enum PointTxnType {

    RECHARGE(Bucket.POINTS, Sign.PLUS),
    ADJUST(Bucket.POINTS, Sign.EITHER),
    CONSUME(Bucket.POINTS, Sign.MINUS),
    FREEZE_OUT(Bucket.POINTS, Sign.MINUS),
    FREEZE_IN(Bucket.FROZEN, Sign.PLUS),
    UNFREEZE_OUT(Bucket.FROZEN, Sign.MINUS),
    UNFREEZE_IN(Bucket.POINTS, Sign.PLUS),
    DEPOSIT_OUT(Bucket.POINTS, Sign.MINUS),
    DEPOSIT_IN(Bucket.DEPOSIT, Sign.PLUS),
    DEPOSIT_BACK_OUT(Bucket.DEPOSIT, Sign.MINUS),
    DEPOSIT_BACK_IN(Bucket.POINTS, Sign.PLUS),
    PREPAY(Bucket.POINTS, Sign.MINUS),
    PREPAY_REFUND(Bucket.POINTS, Sign.PLUS);

    public enum Bucket { POINTS, FROZEN, DEPOSIT }

    public enum Sign { PLUS, MINUS, EITHER }

    private static final Set<PointTxnType> CONSUMING =
            EnumSet.of(CONSUME, FREEZE_OUT, DEPOSIT_OUT, PREPAY);

    private final Bucket bucket;
    private final Sign sign;

    PointTxnType(Bucket bucket, Sign sign) {
        this.bucket = bucket;
        this.sign = sign;
    }

    public Bucket bucket() {
        return bucket;
    }

    public long normalize(long amount) {
        long abs = Math.abs(amount);
        return switch (sign) {
            case PLUS -> abs;
            case MINUS -> -abs;
            case EITHER -> amount;
        };
    }

    public boolean consumesAvailable() {
        return CONSUMING.contains(this);
    }

    public static PointTxnType of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    public static java.util.List<String> namesOf(Bucket bucket) {
        return java.util.Arrays.stream(values())
                .filter(type -> type.bucket() == bucket)
                .map(Enum::name)
                .toList();
    }
}
