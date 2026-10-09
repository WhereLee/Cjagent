package com.wherelee.cabinet.application.reconcile;

import com.wherelee.cabinet.application.reconcile.ReconcileReport.Diff;
import com.wherelee.cabinet.domain.entity.BizPointAccount;
import com.wherelee.cabinet.domain.enums.PointTxnType;
import com.wherelee.cabinet.infrastructure.mapper.BizReconcileMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 对账批次：把"账到底平不平、状态到底一致不一致"算成一份<b>可核对的差异清单</b>。
 *
 * <p>三条立身之本，缺一条这个作业就没意义：
 * <ol>
 *   <li><b>只报告，不改钱。</b>真出错时你需要的是现场，而不是一条被程序补上的流水；
 *       修复动作必须是人工 ADJUST（留操作人、留理由）。自动"修平"等于把 bug 埋进账本。</li>
 *   <li><b>分批，不是一条大 SQL 扫全库。</b>账户 10 万、流水千万行时，
 *       一个巨型 group by 会让对账作业自己变成线上事故。这里按 {@code id} 游标取一批账户，
 *       每批一次聚合 —— 单批代价由 batchSize 决定，与总量无关。</li>
 *   <li><b>判据必须精确到"合法状态"而不是"看起来不对"。</b>悬挂押金只看"订单已终态但凭证未退"；
 *       活动单的格口只把 FREE 判成漂移（FAULT/MAINTENANCE 带存量单是设计内的，见防呆 8）。
 *       判据写宽了，几百条假阳性会把真问题埋掉。</li>
 * </ol>
 *
 * <p>差异同时进两处：日志（给人查）与 Gauge（给看板盯趋势）。Gauge 读的是<b>上一次作业的结果</b>
 * 而不是现算——否则每 15 秒一次的抓取会自己跑一遍全库聚合，监控反而打垮被监控的东西。
 */
@Service
public class ReconcileService {

    private static final Logger log = LoggerFactory.getLogger(ReconcileService.class);

    public static final String LEDGER_IMBALANCE = "ledger-imbalance";
    public static final String HANGING_DEPOSIT = "hanging-deposit";
    public static final String ORDER_WITHOUT_SLOT = "order-without-slot";
    public static final String SLOT_WITHOUT_ORDER = "slot-without-order";
    public static final String STRANDED_ORDER = "stranded-order";
    /** 两边比：通道收了钱但点数没入账（用户损失） */
    public static final String PAID_NOT_CREDITED = "pay-received-not-credited";
    /** 两边比：点数加了但通道没有收款记录（平台损失） */
    public static final String CREDITED_WITHOUT_PAY = "credit-without-pay";

    private final BizReconcileMapper reconcileMapper;
    private final MeterRegistry meterRegistry;

    /** 上一次作业的差异数，按检查项存着给 Gauge 读。 */
    private final Map<String, AtomicLong> lastCounts = new java.util.concurrent.ConcurrentHashMap<>();

    @Value("${cabinet.scheduler.reconcile-batch-size:500}")
    private int batchSize;

    /** 差异样本条数：日志里要给前几条以便定位，但不可能把几千条都打出来。 */
    @Value("${cabinet.scheduler.reconcile-sample-size:5}")
    private int sampleSize;

    public ReconcileService(BizReconcileMapper reconcileMapper, MeterRegistry meterRegistry) {
        this.reconcileMapper = reconcileMapper;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void bindGauges() {
        for (String check : List.of(LEDGER_IMBALANCE, HANGING_DEPOSIT, ORDER_WITHOUT_SLOT,
                SLOT_WITHOUT_ORDER, STRANDED_ORDER, PAID_NOT_CREDITED, CREDITED_WITHOUT_PAY)) {
            AtomicLong holder = lastCounts.computeIfAbsent(check, k -> new AtomicLong(0L));
            Gauge.builder("cabinet.reconcile.diff", holder, AtomicLong::doubleValue)
                    .tag("check", check)
                    .description("上一次对账作业该检查项的差异数（非实时）")
                    .register(meterRegistry);
        }
    }

    /**
     * 对单个租户做一次对账。租户由调用方给（作业在任务自己的上下文里跑，后台手动触发时来自登录态），
     * 这里<b>不读 ThreadLocal</b>：一个纯参数化的方法才能被测试直接喂两个租户号跑对照。
     */
    public ReconcileReport reconcile(Long tenantId) {
        List<Diff> diffs = new ArrayList<>();
        long scanned = ledgerImbalance(tenantId, diffs);
        counts(tenantId, diffs);
        publish(diffs);

        boolean clean = diffs.stream().allMatch(d -> d.count() == 0);
        if (clean) {
            log.info("对账通过 tenant={} 扫描账户={}", tenantId, scanned);
        } else {
            log.warn("对账有差异 tenant={} 扫描账户={} {}", tenantId, scanned,
                    diffs.stream().filter(d -> d.count() > 0)
                            .map(d -> d.check() + "=" + d.count() + (d.samples().isEmpty() ? "" : " 样本[" + d.samples() + "]"))
                            .reduce((a, b) -> a + "; " + b).orElse(""));
        }
        return new ReconcileReport(tenantId, List.copyOf(diffs), scanned);
    }

    /**
     * 不变量 3：两栏余额必须各自等于对应流水之和。
     *
     * @return 扫描过的账户数
     */
    private long ledgerImbalance(Long tenantId, List<Diff> diffs) {
        List<String> pointsTypes = PointTxnType.namesOf(PointTxnType.Bucket.POINTS);
        List<String> frozenTypes = PointTxnType.namesOf(PointTxnType.Bucket.FROZEN);
        long afterId = 0L;
        long scanned = 0L;
        List<String> samples = new ArrayList<>();
        int imbalanced = 0;

        while (true) {
            List<BizPointAccount> batch = reconcileMapper.nextAccountBatch(tenantId, afterId, batchSize);
            if (batch.isEmpty()) {
                break;
            }
            List<Long> customerIds = new ArrayList<>(batch.size());
            for (BizPointAccount account : batch) {
                afterId = Math.max(afterId, account.getId());
                customerIds.add(account.getCustomerId());
            }

            Map<Long, long[]> sums = new HashMap<>(customerIds.size() * 2);
            for (BizReconcileMapper.BucketSum row : reconcileMapper.bucketSums(customerIds, pointsTypes, frozenTypes)) {
                sums.put(row.getCustomerId(), new long[]{nz(row.getSumPoints()), nz(row.getSumFrozen())});
            }

            for (BizPointAccount account : batch) {
                // 没有一行流水是合法状态（新开户），必须按 0 参与比较而不是跳过：
                // 跳过就等于"余额非 0 但没有流水"这类最恶性差异永远不会被报出来
                long[] sum = sums.getOrDefault(account.getCustomerId(), new long[]{0L, 0L});
                boolean bad = account.getPoints() != null && account.getPoints() != sum[0]
                        || account.getFrozenPoints() != null && account.getFrozenPoints() != sum[1];
                if (bad) {
                    imbalanced++;
                    if (samples.size() < sampleSize) {
                        samples.add(account.getCustomerId() + "(可用=" + account.getPoints() + "/Σ=" + sum[0]
                                + ",冻结=" + account.getFrozenPoints() + "/Σ=" + sum[1] + ")");
                    }
                }
            }
            scanned += batch.size();
            if (batch.size() < batchSize) {
                break;
            }
        }
        diffs.add(new Diff(LEDGER_IMBALANCE, imbalanced, String.join(", ", samples)));
        return scanned;
    }

    private void counts(Long tenantId, List<Diff> diffs) {
        diffs.add(new Diff(HANGING_DEPOSIT, reconcileMapper.hangingDeposits(tenantId), ""));
        diffs.add(new Diff(ORDER_WITHOUT_SLOT, reconcileMapper.orphanActiveOrders(tenantId), ""));
        diffs.add(new Diff(SLOT_WITHOUT_ORDER, reconcileMapper.lockedSlotsWithoutOrder(tenantId), ""));
        diffs.add(new Diff(STRANDED_ORDER, reconcileMapper.strandedOrders(tenantId), ""));
        // 两本账互比：这一步才是“对账”而不是“自检”——余额与流水自洽并不能证明钱真的进来了
        diffs.add(new Diff(PAID_NOT_CREDITED, reconcileMapper.paidButNotCredited(tenantId), ""));
        diffs.add(new Diff(CREDITED_WITHOUT_PAY, reconcileMapper.creditedWithoutPay(tenantId), ""));
    }

    private void publish(List<Diff> diffs) {
        for (Diff diff : diffs) {
            lastCounts.computeIfAbsent(diff.check(), k -> new AtomicLong()).set(diff.count());
        }
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }

    /**
     * 悬挂押单的订单号（最多 {@code limit} 条，剩下的下一轮再接）。
     *
     * <p>为什么由对账而不是由下单来拿这份名单：下单时押金挂着是正常态，提前给它登记重试任务
     * 等于每张单都跑一轮“未终态→重试→判死”，把 DEAD 变成噪声；而“单已终态但押金未退”
     * 这个现场只有对账能看见。
     *
     * <p>拿名单不等于改钱：后续的重试 handler 才动账户，而且每一步幂等。
     */
    public List<String> hangingDepositOrderNos(Long tenantId, int limit) {
        return reconcileMapper.findHangingDepositOrderNos(tenantId, limit);
    }
}
