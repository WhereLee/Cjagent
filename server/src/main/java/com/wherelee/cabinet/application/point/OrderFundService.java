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
import java.util.List;

/**
 * 订单侧的资金动作：下单冻结、取件结算与退押金。
 *
 * <p>三个决定性的语义：
 * <ol>
 *   <li><b>先全额解冻、再按实际消耗扣</b>。反过来的话，冻结住的点数会挡住本次消耗，
 *       出现"明明有余额却扣不动"的假象；</li>
 *   <li><b>扣不到就记欠费，但不锁件</b>（防呆红线：用户的东西不能成为讨债的筹码）。
 *       欠费的清偿路径是<b>充值时自动抵扣</b>（见 {@link #repayArrears}）——因为平台侧
 *       对欠费只有一道可执行的手段（挡住下次下单），而“挡住”必须配一个“还完就放行”的出口，
 *       否则拦人就变成了永久拦人；</li>
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
        // 账户级押金（定-1）：交过就不重复划，没交够就从可用余额里划到门槛。
        // 它必须在冻结之前、且在同一个事务里：划不动就是余额不够，整单失败；
        // 不能先占住格口再告诉用户“押金不够”——那会白留一个已分配未支付的格口。
        points.holdAccountDeposit(order.getCustomerId(), depositThreshold(order, rule),
                order.getOrderNo() + ":deposit", "账户级押金");
        // 新单不再有“每单押金”，冻结只剩预估消耗（历史单的押金仍按它自己订单上的金额退）
        long total = Math.max(0L, quote.consumePoints());

        Long frozenTxnId = null;
        if (total > 0) {
            var frozen = points.freeze(order.getCustomerId(), total, REF_ORDER, order.getId(),
                    order.getOrderNo() + ":hold", "预估费用");
            frozenTxnId = frozen.txnId();
        }

        order.setDepositPoints(0L);
        order.setFrozenPoints(total);
        order.setSettledPoints(0L);
        order.setArrearsPoints(0L);
        order.setPricingSnapshot(quote.snapshot());
        // 不再写 biz_deposit：押金已经是账户上的一栅，不是一行一笔“待退”的义务。
        // （旧单的 BizDeposit 行仍由 settle/cancel 按 order.depositPoints 驱动退还，不追溯。）
        return quote;
    }

    /** 这个单该押多少：按点位取生效策略里的门槛，没发布过就用配置默认值。 */
    private long depositThreshold(BizStorageOrder order, com.wherelee.cabinet.domain.entity.BizPriceRule rule) {
        return rule != null ? rule.getDepositPoints() : pricing.accountDepositPoints();
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
     * 用户自助退押金（2026-10-10 定：客户自己操作）。
     *
     * <p>规则是“先用押金抵欠款，剩下的退回；押金不够抵就仍为欠款状态”。
     * 执行顺序不能反：先把押金整个退回可用，再**以押金金额为上限**逐单抵扣欠款——
     * 如果反过来先抵后退，“抵不动”就会把押金卡住（普通清偿的“不足额一分不扣”规则在这里不适用，
     * 因为退押金是用户主动结算，有多少抵多少才是他要的）。
     *
     * @return 真正退回用户可用的点数（= 押金 - 抵扣的欠款）
     */
    @Transactional
    public long refundDeposit(Long customerId, String requestId) {
        long held = points.depositOf(customerId);
        if (held <= 0) {
            throw new BizException(ResultCode.BIZ_ERROR, "当前没有已押的押金，无需退回");
        }
        points.releaseAccountDeposit(customerId, "DEPOSIT-REFUND-" + requestId, "用户自助退押金");
        long offset = repayArrearsUpTo(customerId, held, requestId);
        long back = held - offset;
        log.info("退押金完成 customer={} 押金={} 抵欠={} 退回={}", customerId, held, offset, back);
        return back;
    }

    /**
     * 最多用 cap 这么多点数去抵欠款（与充值自动清偿的区别就在“允许部分抵扣”）。
     * 只抵到 cap 为止，超出的欠款依旧挂着——这就是“押金不够仍是欠款状态”。
     */
    private long repayArrearsUpTo(Long customerId, long cap, String requestId) {
        // biz_no 列宽有限（VARCHAR(64)），而单号本身就 17 位：拼上完整 requestId 会直接超宽插不进去。
        // 取尾 8 位做区分就足够（端点层 @Idempotent 已按 requestId 挡住重复提交）
        String tag = requestId.length() <= 8 ? requestId : requestId.substring(requestId.length() - 8);
        long used = 0L;
        for (BizStorageOrder owed : orderMapper.listOwed(customerId)) {
            if (used >= cap) {
                break;
            }
            long need = owed.getArrearsPoints() == null ? 0L : owed.getArrearsPoints();
            long take = Math.min(need, cap - used);
            if (take <= 0) {
                continue;
            }
            points.post(customerId, PointTxnType.CONSUME, take, REF_ORDER, owed.getId(),
                    owed.getOrderNo() + ":doff:" + tag, "退押金时以押金抵欠");
            if (orderMapper.reduceArrears(owed.getId(), take) == 0) {
                throw new BizException(ResultCode.SYSTEM_ERROR,
                        "抵扣欠款与订单状态不一致，已回滚 orderNo=" + owed.getOrderNo());
            }
            used += take;
        }
        return used;
    }

    /**
     * 欠费清偿：把该客户的欠费单按先欠先还的顺序逐张抵扣。
     *
     * <p><b>为什么必须有这一步</b>：业务规则是“欠费 > 0 就不得再下单”，而用户能做的只有充值。
     * 充值不清欠费的话，这一道拦就变成永久拦——而且服务端文案还写着“请先补缴”，
     * 指向一个不存在的动作（本方法就是补上这个洞）。
     *
     * <p><b>不足额就不动</b>（这是一个可推翻的设计选择，理由写在这里）：欠 200 而用户只充 50 时，
     * 抵扣那 50 的结果是“钱被吃掉、仍然不能下单”，那是最容易被投诉的形态；
     * 不抵则用户手里有钱、欠额不改，他要么充够、要么先用这 50 点干别的（但依旧不能下单）。
     * 两种都拦着下单，但只有一种不会静默吞掉用户的钱。
     *
     * <p>整段一个事务：要么“扣款 + 减欠额”同成，要么同不成。失败回滚后欠费依旧 > 0，
     * 下次充值会重试——这就是为什么 bizNo 能用固定的 {@code 单号:repay}：
     * 上一轮滚回了连流水一起滚回，不会留下“扣了钱却没减欠额”的现场。
     *
     * @return 本次实际清偿的点数
     */
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
            // POSTED 与 DUPLICATE 都表示这笔钱已经动了，所以欠额必须同步减；
            // 把 DUPLICATE 当失败会造成“钱扣了、台账还挂着欠费”——那比报错危险
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
