package com.wherelee.cabinet.funds.app;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.funds.app.PricingPolicy;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.funds.domain.BizDeposit;
import com.wherelee.cabinet.funds.domain.BizPointAccount;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.funds.api.DepositStatus;
import com.wherelee.cabinet.domain.enums.OrderCloseReason;
import com.wherelee.cabinet.domain.enums.OrderStatus;
import com.wherelee.cabinet.funds.api.PointTxnType;
import com.wherelee.cabinet.locker.api.SlotPort;
import com.wherelee.cabinet.funds.infra.BizDepositMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class OrderFundService {

    private static final Logger log = LoggerFactory.getLogger(OrderFundService.class);

    public static final String REF_ORDER = "STORAGE_ORDER";

    private final PointAccountService points;
    private final PricingPolicy pricing;
    private final com.wherelee.cabinet.funds.app.PriceRuleService priceRules;
    private final BizDepositMapper depositMapper;
    private final BizStorageOrderMapper orderMapper;
    private final SlotPort slots;

    public OrderFundService(PointAccountService points, PricingPolicy pricing,
                            com.wherelee.cabinet.funds.app.PriceRuleService priceRules,
                            BizDepositMapper depositMapper, BizStorageOrderMapper orderMapper,
                            SlotPort slots) {
        this.points = points;
        this.pricing = pricing;
        this.priceRules = priceRules;
        this.depositMapper = depositMapper;
        this.orderMapper = orderMapper;
        this.slots = slots;
    }

    @Transactional
    public void holdFunds(BizStorageOrder order) {
        com.wherelee.cabinet.funds.domain.BizPriceRule rule = priceRules.effective(order.getSiteId());
        long prepay = pricing.prepayPoints(order.getSizeType(), rule);
        points.post(order.getCustomerId(), PointTxnType.PREPAY, prepay,
                REF_ORDER, order.getId(), order.getOrderNo() + ":prepay", "预收四小时费用");

        order.setDepositPoints(0L);
        order.setFrozenPoints(0L);
        order.setPrepayPoints(prepay);
        order.setSettledPoints(0L);
        order.setArrearsPoints(0L);
        order.setPricingSnapshot(pricing.inputSnapshot(order.getSizeType(), rule));
    }

    public int capDaysOf(String snapshot) {
        return pricing.capDaysOf(snapshot);
    }

    @Transactional
    public Settlement settle(BizStorageOrder order, long actualMinutes) {
        return settle(order, actualMinutes, OrderCloseReason.NORMAL);
    }

    @Transactional
    public Settlement settle(BizStorageOrder order, long actualMinutes, OrderCloseReason reason) {
        PricingPolicy.Quote quote = pricing.fromSnapshot(order.getPricingSnapshot(), actualMinutes);
        long need = quote.consumePoints();
        unfreezeAndRefundDeposit(order, "取件解冻");

        long prepay = order.getPrepayPoints() == null ? 0L : order.getPrepayPoints();
        long arrears;
        long charged;
        if (prepay >= need) {
            charged = need;
            long refund = prepay - need;
            if (refund > 0) {
                points.post(order.getCustomerId(), PointTxnType.PREPAY_REFUND, refund,
                        REF_ORDER, order.getId(), order.getOrderNo() + ":prepay-refund", "预收多退");
            }
            arrears = 0L;
        } else {
            long additional = need - prepay;
            var attempt = points.tryPost(order.getCustomerId(), PointTxnType.CONSUME, additional,
                    REF_ORDER, order.getId(), order.getOrderNo() + ":consume", "预收不足，补扣差额");
            long extra = attempt.status() == PointAccountService.PostStatus.POSTED
                    ? additional : chargeWhatWeCan(order, additional);
            charged = prepay + extra;
            arrears = additional - extra;
            if (arrears > 0) {
                log.warn("补扣不足，记未支付待追缴 orderNo={} need={} prepay={} extra={} arrears={}",
                        order.getOrderNo(), need, prepay, extra, arrears);
            }
        }

        order.setSettledPoints(charged);
        order.setArrearsPoints(arrears);
        order.setFrozenPoints(0L);
        order.setCloseReason(reason == null ? OrderCloseReason.NORMAL : reason);
        if (order.getStatus() != OrderStatus.SETTLING) {
            order.transitTo(OrderStatus.SETTLING);
        }
        order.transitTo(OrderStatus.CLOSED);
        order.setFinishedAt(LocalDateTime.now());
        if (orderMapper.updateById(order) == 0) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "订单已被并发修改，结算需重试 orderNo=" + order.getOrderNo());
        }

        boolean released = slots.releaseOccupied(order.getSlotId(), order.getId());
        if (!released) {
            released = slots.releaseReserved(order.getSlotId(), order.getId());
        }
        if (!released) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "格口状态与订单不一致，需人工核对 slotId="
                    + order.getSlotId());
        }
        return new Settlement(need, arrears, quote.billedHours(), actualMinutes, 0L);
    }

    private long chargeWhatWeCan(BizStorageOrder order, long need) {
        return chargeWhatWeCan(order, need, ":consume-partial");
    }

    private long chargeWhatWeCan(BizStorageOrder order, long need, String suffix) {
        BizPointAccount account = points.accountOf(order.getCustomerId());
        long available = account == null || account.getPoints() == null ? 0L : account.getPoints();
        long tryNow = Math.min(available, need);
        if (tryNow <= 0) {
            return 0L;
        }
        var attempt = points.tryPost(order.getCustomerId(), PointTxnType.CONSUME, tryNow,
                REF_ORDER, order.getId(), order.getOrderNo() + suffix, "余额不足，按可用额扣");
        if (attempt.status() == PointAccountService.PostStatus.POSTED) {
            return tryNow;
        }
        log.warn("补扣再次失败，本单全额计欠费 orderNo={}", order.getOrderNo());
        return 0L;
    }

    @Transactional
    public long repayArrears(Long customerId) {
        List<BizStorageOrder> owed = orderMapper.listOwed(customerId);
        if (owed.isEmpty()) {
            return 0L;
        }
        long repaid = 0L;
        for (BizStorageOrder order : owed) {
            long need = order.getArrearsPoints() == null ? 0L : order.getArrearsPoints();
            if (need <= 0) {
                continue;
            }
            var attempt = points.tryPost(customerId, PointTxnType.CONSUME, need, REF_ORDER, order.getId(),
                    order.getOrderNo() + ":repay", "欠费补缴");
            if (attempt.status() == PointAccountService.PostStatus.INSUFFICIENT) {
                log.info("余额不够清偿，本轮不抵扣 customer={} 待还={} 可用={}",
                        customerId, need, attempt.balanceAfter());
                break;
            }
            if (orderMapper.reduceArrears(order.getId(), need) == 0) {
                throw new BizException(ResultCode.SYSTEM_ERROR,
                        "欠费清偿状态不一致，已回滚本次抵扣 orderNo=" + order.getOrderNo());
            }
            repaid += need;
            log.info("欠费清偿完成 orderNo={} 金额={} 类型={}",
                    order.getOrderNo(), need, attempt.status());
        }
        return repaid;
    }

    @Transactional
    public void cancelHold(BizStorageOrder order) {
        unfreezeAndRefundDeposit(order, "取消解冻");
        long prepay = order.getPrepayPoints() == null ? 0L : order.getPrepayPoints();
        if (prepay > 0) {
            points.post(order.getCustomerId(), PointTxnType.PREPAY_REFUND, prepay,
                    REF_ORDER, order.getId(), order.getOrderNo() + ":prepay-refund", "下单未成，预收全额退回");
            order.setPrepayPoints(0L);
        }
        order.setFrozenPoints(0L);
        order.setSettledPoints(0L);
    }

    private void unfreezeAndRefundDeposit(BizStorageOrder order, String remark) {
        long frozen = order.getFrozenPoints() == null ? 0L : order.getFrozenPoints();
        if (frozen > 0) {
            points.unfreeze(order.getCustomerId(), frozen, REF_ORDER, order.getId(),
                    order.getOrderNo() + ":release", remark);
        }
        BizDeposit deposit = depositMapper.selectOne(Wrappers.<BizDeposit>lambdaQuery()
                .eq(BizDeposit::getOrderId, order.getId()));
        if (deposit != null && deposit.getStatus() != DepositStatus.REFUNDED) {
            int moved = depositMapper.markRefunded(deposit.getId(), deposit.getHeldTxnId(), LocalDateTime.now());
            if (moved == 0) {
                log.info("押金已被其它路径退还，跳过 orderNo={}", order.getOrderNo());
            }
        }
        order.setFrozenPoints(0L);
    }

    @Transactional
    public int refundHangingDeposit(BizStorageOrder order) {
        BizDeposit deposit = depositMapper.selectOne(Wrappers.<BizDeposit>lambdaQuery()
                .eq(BizDeposit::getOrderId, order.getId()));
        if (deposit == null || deposit.getStatus() == DepositStatus.REFUNDED) {
            return 0;
        }
        long frozen = order.getFrozenPoints() == null ? 0L : order.getFrozenPoints();
        if (frozen > 0) {
            unfreezeAndRefundDeposit(order, "押金退还重试");
            if (orderMapper.updateById(order) == 0) {
                throw new BizException(ResultCode.SYSTEM_ERROR, "订单已被并发修改，押金重试待下一轮");
            }
        } else {
            depositMapper.markRefunded(deposit.getId(), deposit.getHeldTxnId(), LocalDateTime.now());
        }
        return deposit.getPoints() == null ? 0 : deposit.getPoints().intValue();
    }

    public long unfinishedDeposits() {
        Long count = depositMapper.selectCount(Wrappers.<BizDeposit>lambdaQuery()
                .in(BizDeposit::getStatus, DepositStatus.HELD, DepositStatus.REFUNDING, DepositStatus.FAILED));
        return count == null ? 0L : count;
    }

    public record Settlement(long consumePoints, long arrearsPoints, int billedHours, long actualMinutes,
                             long penaltyPoints) {
    }
}
