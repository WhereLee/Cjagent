package com.wherelee.cabinet.storage.app;

import java.util.concurrent.ThreadLocalRandom;

public final class VoucherCodes {

    private VoucherCodes() {
    }

    public static String newVoucher() {
        int random = ThreadLocalRandom.current().nextInt(10000000, 100000000);
        return String.format("V%08d", random);
    }
}
