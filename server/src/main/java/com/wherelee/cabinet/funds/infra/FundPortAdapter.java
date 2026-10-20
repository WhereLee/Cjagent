package com.wherelee.cabinet.funds.infra;

import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.OrderCloseReason;
import com.wherelee.cabinet.funds.app.OrderFundService;
import com.wherelee.cabinet.storage.api.FundPort;
import org.springframework.stereotype.Component;

/**
 * {@link FundPort} 的进程内实现：直接委托 {@link OrderFundService}。
 *
 * <p>它是这轮重构里唯一允许碰订单实体的资金类：金额算法与事务边界都在
 * {@code OrderFundService} 里，适配器不复制、不改写，只做“端口 → 实现”的接线。
 * 将来拆进程时，替换的只是这个类（改成调订单服务/资金服务的远程接口）。
 */
@Component
public class FundPortAdapter implements FundPort {

    private final OrderFundService funds;

    public FundPortAdapter(OrderFundService funds) {
        this.funds = funds;
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
}
