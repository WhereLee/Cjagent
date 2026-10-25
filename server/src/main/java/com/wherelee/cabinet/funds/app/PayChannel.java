package com.wherelee.cabinet.funds.app;

public interface PayChannel {

    String name();

    String createOrder(String outTradeNo, long points, long amountFen);

    PayOutcome query(String outTradeNo);

    record PayOutcome(boolean paid, String tradeNo, String reason) {

        public static PayOutcome paid(String tradeNo) {
            return new PayOutcome(true, tradeNo, null);
        }

        public static PayOutcome notYet(String reason) {
            return new PayOutcome(false, null, reason);
        }
    }
}
