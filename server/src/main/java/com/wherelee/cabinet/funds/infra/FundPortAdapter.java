package com.wherelee.cabinet.funds.infra;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.OrderCloseReason;
import com.wherelee.cabinet.funds.app.OrderFundService;
import com.wherelee.cabinet.funds.app.PointAccountService;
import com.wherelee.cabinet.funds.app.PriceRuleService;
import com.wherelee.cabinet.funds.app.PricingPolicy;
import com.wherelee.cabinet.funds.domain.BizPointTxn;
import com.wherelee.cabinet.funds.domain.BizPriceRule;
import com.wherelee.cabinet.storage.api.FundPort;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * {@link FundPort} 的进程内实现：直接委托资金用例。
 *
 * <p>它是这轮重构里唯一允许碰订单实体的资金类：金额算法与事务边界都在
 * {@code OrderFundService} 里，适配器不复制、不改写，只做“端口 → 实现”的接线。
 * 将来拆进程时，替换的只是这个类（改成调订单服务/资金服务的远程接口）。
 */
@Component
public class FundPortAdapter implements FundPort {

    private final OrderFundService funds;
    private final PointAccountService accounts;
    private final PriceRuleService priceRules;
    private final PricingPolicy pricing;
    private final BizPointTxnMapper txnMapper;

    public FundPortAdapter(OrderFundService funds, PointAccountService accounts, PriceRuleService priceRules,
                           PricingPolicy pricing, BizPointTxnMapper txnMapper) {
        this.funds = funds;
        this.accounts = accounts;
        this.priceRules = priceRules;
        this.pricing = pricing;
        this.txnMapper = txnMapper;
    }

    @Override
    public void holdFunds(BizStorageOrder order) {
        funds.holdFunds(order);
    }

    @Override
    public void cancelHold(BizStorageOrder order) {
        funds.cancelHold(order);
    }

    @Override
    public void settle(BizStorageOrder order, long actualMinutes, OrderCloseReason reason) {
        funds.settle(order, actualMinutes, reason);
    }

    @Override
    public int capDaysOf(String pricingSnapshot) {
        return funds.capDaysOf(pricingSnapshot);
    }

    @Override
    public int refundHangingDeposit(BizStorageOrder order) {
        return funds.refundHangingDeposit(order);
    }

    @Override
    public int toleranceMinutesOf(Long siteId) {
        BizPriceRule rule = priceRules.effective(siteId);
        return rule == null || rule.getToleranceMinutes() == null ? -1 : rule.getToleranceMinutes();
    }

    @Override
    public PriceParams priceParamsOf(String pricingSnapshot) {
        PricingPolicy.PricingParams params = pricing.snapshotParams(pricingSnapshot);
        return new PriceParams(params.unitPointsPerHour(), params.freeMinutes(),
                params.dailyCapHours(), params.capDays());
    }

    @Override
    public PriceQuote quoteOf(String pricingSnapshot, long actualMinutes) {
        PricingPolicy.Quote quote = pricing.fromSnapshot(pricingSnapshot, actualMinutes);
        return new PriceQuote(quote.billedHours(), quote.consumePoints());
    }

    @Override
    public AccountView accountOf(Long customerId) {
        var account = accounts.accountOf(customerId);
        return account == null ? null
                : new AccountView(account.getPoints(), account.getFrozenPoints(), account.getDepositPoints());
    }

    @Override
    public List<TxnLine> txnsOf(Long orderId, int limit) {
        if (orderId == null) {
            return List.of();
        }
        return txnMapper.selectList(Wrappers.<BizPointTxn>lambdaQuery()
                        .eq(BizPointTxn::getRefType, OrderFundService.REF_ORDER)
                        .eq(BizPointTxn::getRefId, orderId)
                        .orderByAsc(BizPointTxn::getCreateTime)
                        .last("limit " + Math.max(1, Math.min(limit, 200))))
                .stream()
                .map(t -> new TxnLine(t.getBizType() == null ? null : t.getBizType().name(),
                        t.getAmount() == null ? 0L : t.getAmount(),
                        t.getBalanceAfter() == null ? 0L : t.getBalanceAfter(),
                        t.getRemark(), t.getCreateTime()))
                .toList();
    }
}
