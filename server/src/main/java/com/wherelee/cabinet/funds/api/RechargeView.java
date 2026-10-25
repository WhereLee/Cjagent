package com.wherelee.cabinet.funds.api;

import com.wherelee.cabinet.funds.api.PayTxnStatus;

public record RechargeView(String outTradeNo, long points, long balance,
                           String channel, String status) {

    public boolean credited() {
        return PayTxnStatus.PAID.name().equals(status);
    }
}
