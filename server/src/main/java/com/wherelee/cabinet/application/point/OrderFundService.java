package com.wherelee.cabinet.application.point;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.application.billing.PricingPolicy;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.entity.BizDeposit;
import com.wherelee.cabinet.domain.entity.BizPointAccount;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.DepositStatus;
import com.wherelee.cabinet.domain.enums.OrderCloseReason;
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
    /** 下单时取当前生效的计价策略（第 14D 刀）。拿不到则退回配置默认价，不是拿 0 当价。 */
    private final com.wherelee.cabinet.application.billing.PriceRuleService priceRules;
    private final BizDepositMapper depositMapper;
    private final BizStorageOrderMapper orderMapper;
    private final BizCompartmentMapper slotMapper;

    public OrderFundService(PointAccountService points, PricingPolicy pricing,
                            com.wherelee.cabinet.application.billing.PriceRuleService priceRules,
                            BizDepositMapper depositMapper, BizStorageOrderMapper orderMapper,
                            BizCompartmentMapper slotMapper) {
        this.points = points;
        this.pricing = pricing;
        this.priceRules = priceRules;
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
        // 价只在这一刻读策略表：算完就写进快照，之后结算、逾期、封顶全读快照。
        // 所以发布/回滚影响不到这张已经下出去的单（这是第 12 刀定的口径，本刀没改它）。
        com.wherelee.cabinet.domain.entity.BizPriceRule rule = priceRules.effective(order.getSiteId());
        PricingPolicy.Quote quote = rule == null
                ? pricing.quote(order.getSizeType(), estimateMinutes)
                : pricing.quoteByRule(order.getSizeType(), estimateMinutes, rule);
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
     * 取件结算（正常结束）。
     *
     * @param actualMinutes 必须由服务端算（计费起点到此刻），<b>不能信客户端上报</b>
     */
    @Transactional
    public Settlement settle(BizStorageOrder order, long actualMinutes) {
        return settle(order, actualMinutes, OrderCloseReason.NORMAL);
    }

    /**
     * 结算的完整入口：带上“这单是怎么结的”，因为不同结束原因的<b>价格不一样</b>。
     *
     * <p>加收单独走一条流水（{@code 单号:remote-fee}）而不是合进计时费用里，有三个理由：
     * ① 账单要能拆开解释“哪一部分是租金、哪一部分是未关门加收”；
     * ② 幂等键分开，重跑结算时不会因 bizNo 相同而把两笔当成一笔；
     * ③ 报表与对账能直接按类型查出“加收收了多少钱”。
     */
    @Transactional
    public Settlement settle(BizStorageOrder order, long actualMinutes, OrderCloseReason reason) {
        PricingPolicy.Quote quote = pricing.fromSnapshot(order.getPricingSnapshot(), actualMinutes);
        long need = quote.consumePoints();
        long penalty = reason != null && reason.chargesPenalty() ? pricing.remoteFeePoints(order.getPricingSnapshot()) : 0L;
        if (penalty > 0) {
            order.setRemoteClosePoints(penalty);
        }
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

        // 加收与计时费同路走“扣不到就记欠费”，但绝不为这笔钱锁件（防呆红线不变）
        if (penalty > 0) {
            var feeAttempt = points.tryPost(order.getCustomerId(), PointTxnType.CONSUME, penalty,
                    REF_ORDER, order.getId(), order.getOrderNo() + ":remote-fee",
                    "未关门离开加收费用（按该格口小时单价折算）");
            if (feeAttempt.status() == PointAccountService.PostStatus.POSTED) {
                charged += penalty;
            } else {
                long feeCharged = chargeWhatWeCan(order, penalty, ":remote-fee-partial");
                charged += feeCharged;
                arrears += penalty - feeCharged;
                log.warn("加收扣不足，记欠费待追缴 orderNo={} penalty={} charged={}",
                        order.getOrderNo(), penalty, feeCharged);
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
            // 带 @Version 的实体“更新 0 行”不报错，忽略它就是静默丢数据（第 12 刀刚因此丢过定价快照）
            throw new BizException(ResultCode.SYSTEM_ERROR, "订单已被并发修改，结算需重试 orderNo=" + order.getOrderNo());
        }

        int released = slotMapper.releaseOccupiedSlot(order.getSlotId(), order.getId());
        if (released == 0) {
            // 结完算却释放不掉：说明格口已被别人改写，必须响而不是把账做平就收工
            throw new BizException(ResultCode.SYSTEM_ERROR, "格口状态与订单不一致，需人工核对 slotId="
                    + order.getSlotId());
        }
        return new Settlement(need, arrears, quote.billedHours(), actualMinutes, penalty);
    }

    /** 扣不到全款时，能扣多少扣多少（余额为 0 就一分不扣），返回实际扣到的点数。 */
    private long chargeWhatWeCan(BizStorageOrder order, long need) {
        return chargeWhatWeCan(order, need, ":consume-partial");
    }

    /**
     * 按可用额量扣。<b>bizNo 后缀必须由调用方给</b>：租金与加收共用一个后缀的话，
     * 唯一幂等键会让第二笔被当成“已处理”静默丢弃（收不到钱还不报错）。
     */
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

    /**
     * 回收悬挂押金（不变量 4 的安全网，由延迟任务/对账重试触发）。
     *
     * @return 本次退还的押金点数；0 表示本来就没得退（幂等重跑的正常分支）
     */
    @Transactional
    public int refundHangingDeposit(BizStorageOrder order) {
        BizDeposit deposit = depositMapper.selectOne(Wrappers.<BizDeposit>lambdaQuery()
                .eq(BizDeposit::getOrderId, order.getId()));
        if (deposit == null || deposit.getStatus() == DepositStatus.REFUNDED) {
            return 0;
        }
        long frozen = order.getFrozenPoints() == null ? 0L : order.getFrozenPoints();
        if (frozen > 0) {
            // 还有冻结在账上：走同一段解冻+改凭证逻辑，不另写一遍退钱路径
            unfreezeAndRefundDeposit(order, "押金退还重试");
            if (orderMapper.updateById(order) == 0) {
                throw new BizException(ResultCode.SYSTEM_ERROR, "订单已被并发修改，押金重试待下一轮");
            }
        } else {
            // 钱已经不在冻结栏（正常结算路径已解冻），只差凭证没改态：补改即可，不能重复退钱
            depositMapper.markRefunded(deposit.getId(), deposit.getHeldTxnId(), LocalDateTime.now());
        }
        return deposit.getPoints() == null ? 0 : deposit.getPoints().intValue();
    }

    /** 是否存在未退押金（不变量 4 的判据之一）。 */
    public long unfinishedDeposits() {
        Long count = depositMapper.selectCount(Wrappers.<BizDeposit>lambdaQuery()
                .in(BizDeposit::getStatus, DepositStatus.HELD, DepositStatus.REFUNDING, DepositStatus.FAILED));
        return count == null ? 0L : count;
    }

    /**
     * @param consumePoints 计时应缴（不含加收）
     * @param penaltyPoints 加收应缴（远程结束/超窗重开），0 = 没收这一笔
     */
    public record Settlement(long consumePoints, long arrearsPoints, int billedHours, long actualMinutes,
                             long penaltyPoints) {
    }
}
