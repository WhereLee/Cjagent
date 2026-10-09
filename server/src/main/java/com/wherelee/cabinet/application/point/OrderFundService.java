package com.wherelee.cabinet.application.point;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.application.billing.PricingPolicy;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.entity.BizDeposit;
import com.wherelee.cabinet.domain.entity.BizPointAccount;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.DepositStatus;
import com.wherelee.cabinet.domain.enums.OrderStatus;
import com.wherelee.cabinet.domain.enums.PointTxnType;
import com.wherelee.cabinet.infrastructure.mapper.BizCompartmentMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizDepositMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 订单侧的资金动作：下单冻结、取件结算与退押金。
 *
 * <p>三个决定性的语义：
 * <ol>
 *   <li><b>先全额解冻、再按实际消耗扣</b>。反过来的话，冻结住的点数会挡住本次消耗，
 *       出现"明明有余额却扣不动"的假象；</li>
 *   <li><b>扣不到就记欠费，但不锁件</b>（防呆红线：用户的东西不能成为讨债的筹码）。
 *       欠费走 arrears 字段，追缴由后续风控/调度处理；</li>
 *   <li><b>押金与消耗分栏</b>：退押金是解冻，不能拿去抵欠费（否则押金就失去了担保语义）。</li>
 * </ol>
 *
 * <p>全程同一事务：冻结/消耗/流水/订单状态要么一起成，要么一起不成——
 * 这里没有任何外部 I/O，所以不适用第 11 刀"等回执不得持事务"的那条拆法。
 */
@Service
public class OrderFundService {

    private static final Logger log = LoggerFactory.getLogger(OrderFundService.class);

    private static final String REF_ORDER = "STORAGE_ORDER";

    private final PointAccountService points;
    private final PricingPolicy pricing;
    private final BizDepositMapper depositMapper;
    private final BizStorageOrderMapper orderMapper;
    private final BizCompartmentMapper slotMapper;

    public OrderFundService(PointAccountService points, PricingPolicy pricing,
                            BizDepositMapper depositMapper, BizStorageOrderMapper orderMapper,
                            BizCompartmentMapper slotMapper) {
        this.points = points;
        this.pricing = pricing;
        this.depositMapper = depositMapper;
        this.orderMapper = orderMapper;
        this.slotMapper = slotMapper;
    }

    /**
     * 下单资金冻结：押金 + 预估费用。点数不够直接失败（此时格口占用会随事务回滚释放）。
     *
     * @return 定价快照要回写到订单上，结算只能用它
     */
    @Transactional
    public PricingPolicy.Quote holdFunds(BizStorageOrder order, long estimateMinutes) {
        PricingPolicy.Quote quote = pricing.quote(order.getSizeType(), estimateMinutes);
        long total = quote.depositPoints() + quote.consumePoints();
        if (total <= 0) {
            // 免费窗口内不冻结也要走通：只冻结押金
            total = quote.depositPoints();
        }

        var frozen = points.freeze(order.getCustomerId(), total, REF_ORDER, order.getId(),
                order.getOrderNo() + ":hold", "押金 + 预估费用");

        order.setDepositPoints(quote.depositPoints());
        order.setFrozenPoints(total);
        order.setSettledPoints(0L);
        order.setArrearsPoints(0L);
        order.setPricingSnapshot(quote.snapshot());

        BizDeposit deposit = new BizDeposit();
        deposit.setTenantId(order.getTenantId());
        deposit.setOrderId(order.getId());
        deposit.setCustomerId(order.getCustomerId());
        deposit.setPoints(quote.depositPoints());
        deposit.setStatus(DepositStatus.HELD);
        deposit.setHeldTxnId(frozen.txnId());
        deposit.setHeldAt(LocalDateTime.now());
        depositMapper.insert(deposit);
        return quote;
    }

    /**
     * 取件结算。
     *
     * @param actualMinutes 必须由服务端算（下单到取件的墙钟），<b>不能信客户端上报</b>
     */
    @Transactional
    public Settlement settle(BizStorageOrder order, long actualMinutes) {
        PricingPolicy.Quote quote = pricing.fromSnapshot(order.getPricingSnapshot(), actualMinutes);
        long need = quote.consumePoints();
        long frozen = order.getFrozenPoints() == null ? 0L : order.getFrozenPoints();
        long arrears = 0L;
        long charged = 0L;

        unfreezeAndRefundDeposit(order, "取件解冻");

        // ② 按实际消耗扣。“余额不够”是业务分支而不是异常，所以走不抛的 tryPost：
        //   靠 catch 嵌套事务抛出的异常会把整个物理事务标成 rollback-only，提交时必爆
        if (need > 0) {
            var attempt = points.tryPost(order.getCustomerId(), PointTxnType.CONSUME, need,
                    REF_ORDER, order.getId(), order.getOrderNo() + ":consume", "实际寄存费用");
            if (attempt.status() == PointAccountService.PostStatus.POSTED) {
                charged = need;
            } else {
                charged = chargeWhatWeCan(order, need);
                arrears = need - charged;
                // 欠费只记账、不锁件：东西在用户手里才是本域最重要的事
                log.warn("取件时点数不足，记欠费待追缴 orderNo={} need={} charged={} arrears={}",
                        order.getOrderNo(), need, charged, arrears);
            }
        }

        long depositPoints = order.getDepositPoints() == null ? 0L : order.getDepositPoints();
        if (depositPoints <= 0) {
            log.warn("押金为 0 的单被标记退还，需核对 orderNo={}", order.getOrderNo());
        }

        order.setSettledPoints(charged);
        order.setArrearsPoints(arrears);
        order.setFrozenPoints(0L);
        if (order.getStatus() != OrderStatus.SETTLING) {
            order.transitTo(OrderStatus.SETTLING);
        }
        order.transitTo(OrderStatus.CLOSED);
        order.setFinishedAt(LocalDateTime.now());
        if (orderMapper.updateById(order) == 0) {
            // 带 @Version 的实体“更新 0 行”不报错，忽略它就是静默丢数据（第 12 刀刚因此丢过定价快照）
            throw new BizException(ResultCode.SYSTEM_ERROR, "订单已被并发修改，结算需重试 orderNo=" + order.getOrderNo());
        }

        int released = slotMapper.releaseOccupiedSlot(order.getSlotId(), order.getId());
        if (released == 0) {
            // 结完算却释放不掉：说明格口已被别人改写，必须响而不是把账做平就收工
            throw new BizException(ResultCode.SYSTEM_ERROR, "格口状态与订单不一致，需人工核对 slotId="
                    + order.getSlotId());
        }
        return new Settlement(need, arrears, quote.billedHours(), actualMinutes);
    }

    /** 扣不到全款时，能扣多少扣多少（余额为 0 就一分不扣），返回实际扣到的点数。 */
    private long chargeWhatWeCan(BizStorageOrder order, long need) {
        BizPointAccount account = points.accountOf(order.getCustomerId());
        long available = account == null || account.getPoints() == null ? 0L : account.getPoints();
        long tryNow = Math.min(available, need);
        if (tryNow <= 0) {
            return 0L;
        }
        var attempt = points.tryPost(order.getCustomerId(), PointTxnType.CONSUME, tryNow,
                REF_ORDER, order.getId(), order.getOrderNo() + ":consume-partial", "余额不足，按可用额扣");
        if (attempt.status() == PointAccountService.PostStatus.POSTED) {
            return tryNow;
        }
        // 读余额与扣减之间被别人花掉了：少收而不是多收，方向安全，交给对账
        log.warn("补扣再次失败，本单全额计欠费 orderNo={}", order.getOrderNo());
        return 0L;
    }

    /**
     * 取消时退还全部冻结（押金 + 预估）。
     *
     * <p>取消与取件的退款必须走同一段代码：否则两边各自维护一遍，“改了取件忘了取消”
     * 这类问题的结果就是押金悬挂不退（不变量 4 要盯的东西）。
     */
    @Transactional
    public void cancelHold(BizStorageOrder order) {
        unfreezeAndRefundDeposit(order, "取消解冻");
        order.setFrozenPoints(0L);
        order.setSettledPoints(0L);
    }

    /** 全额解冻 + 押金凭证改态。两栏各自守恒，不会留半条流水。 */
    private void unfreezeAndRefundDeposit(BizStorageOrder order, String remark) {
        long frozen = order.getFrozenPoints() == null ? 0L : order.getFrozenPoints();
        if (frozen > 0) {
            points.unfreeze(order.getCustomerId(), frozen, REF_ORDER, order.getId(),
                    order.getOrderNo() + ":release", remark);
        }
        BizDeposit deposit = depositMapper.selectOne(Wrappers.<BizDeposit>lambdaQuery()
                .eq(BizDeposit::getOrderId, order.getId()));
        if (deposit != null && deposit.getStatus() != DepositStatus.REFUNDED) {
            // 押金已随上面的统一解冻回到可用栏，这里只把凭证状态改掉
            int moved = depositMapper.markRefunded(deposit.getId(), deposit.getHeldTxnId(), LocalDateTime.now());
            if (moved == 0) {
                log.info("押金已被其它路径退还，跳过 orderNo={}", order.getOrderNo());
            }
        }
        order.setFrozenPoints(0L);
    }

    /** 是否存在未退押金（不变量 4 的判据之一）。 */
    public long unfinishedDeposits() {
        Long count = depositMapper.selectCount(Wrappers.<BizDeposit>lambdaQuery()
                .in(BizDeposit::getStatus, DepositStatus.HELD, DepositStatus.REFUNDING, DepositStatus.FAILED));
        return count == null ? 0L : count;
    }

    public record Settlement(long consumePoints, long arrearsPoints, int billedHours, long actualMinutes) {
    }
}
