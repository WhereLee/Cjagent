package com.wherelee.cabinet.storage.api;

import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.OrderCloseReason;

import java.time.LocalDateTime;
import java.util.List;

public interface FundPort {

    void holdFunds(BizStorageOrder order);

    void cancelHold(BizStorageOrder order);

    void settle(BizStorageOrder order, long actualMinutes, OrderCloseReason reason);

    int capDaysOf(String pricingSnapshot);

    boolean isCapped(String pricingSnapshot, long heldMinutes);

    int refundHangingDeposit(BizStorageOrder order);

    int toleranceMinutesOf(Long siteId);

    PriceParams priceParamsOf(String pricingSnapshot);

    PriceQuote quoteOf(String pricingSnapshot, long actualMinutes);

    List<TxnLine> txnsOf(Long orderId, int limit);

    AccountView accountOf(Long customerId);

    record AccountView(Long points, Long frozenPoints, Long depositPoints) {
    }

    record PriceParams(long unitPointsPerHour, int freeMinutes, int dailyCapHours, int capDays) {
    }

    record PriceQuote(int billedHours, long consumePoints) {
    }

    record TxnLine(String bizType, long amount, long balanceAfter, String remark, LocalDateTime createTime) {
    }
}
