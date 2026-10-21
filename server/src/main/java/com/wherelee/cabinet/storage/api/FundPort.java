package com.wherelee.cabinet.storage.api;

import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.OrderCloseReason;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 资金端口：订单侧只描述“要做的资金动作”，不碰账户、流水与价策。
 *
 * <p>为什么入参还是订单实体：押金、预收、结算、欠费这几列就落在订单上，并且和订单状态
 * 在同一事务、同一乐观锁版本里推进。换成 DTO 会把“同一行数据的两个写者”藏起来——
 * 那比现在的显式耦合更危险。收口办法是：只有本端口的适配器允许碰这些列。
 */
public interface FundPort {

    void holdFunds(BizStorageOrder order);

    void cancelHold(BizStorageOrder order);

    void settle(BizStorageOrder order, long actualMinutes, OrderCloseReason reason);

    int capDaysOf(String pricingSnapshot);

    /** 这张单的计费是否已达封顶（门长开/逾期看管用它决定“再盯也不会多收一分”）。 */
    boolean isCapped(String pricingSnapshot, long heldMinutes);

    int refundHangingDeposit(BizStorageOrder order);

    /** 计时容忍时长（分钟）：算价与计时必须同一版策略，不能各取一套。 */
    int toleranceMinutesOf(Long siteId);

    /** 账单快照里的计价参数：用户端与后台共用一份口径。 */
    PriceParams priceParamsOf(String pricingSnapshot);

    /** 按快照与实际用时算价（结算与账单展示同一套算法）。 */
    PriceQuote quoteOf(String pricingSnapshot, long actualMinutes);

    /** 这张单的资金流水（旧→新，最多 limit 条）。 */
    List<TxnLine> txnsOf(Long orderId, int limit);

    /** 钱包三栏（可用/冻结/押金）；新用户首次入账前没有账户行，返回 null。 */
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
