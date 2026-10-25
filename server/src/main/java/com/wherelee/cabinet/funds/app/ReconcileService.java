package com.wherelee.cabinet.funds.app;

import com.wherelee.cabinet.funds.app.ReconcileReport.Diff;
import com.wherelee.cabinet.funds.domain.BizPointAccount;
import com.wherelee.cabinet.funds.api.PointTxnType;
import com.wherelee.cabinet.funds.infra.BizReconcileMapper;
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

@Service
public class ReconcileService {

    private static final Logger log = LoggerFactory.getLogger(ReconcileService.class);

    public static final String LEDGER_IMBALANCE = "ledger-imbalance";
    public static final String HANGING_DEPOSIT = "hanging-deposit";
    public static final String ORDER_WITHOUT_SLOT = "order-without-slot";
    public static final String SLOT_WITHOUT_ORDER = "slot-without-order";
    public static final String STRANDED_ORDER = "stranded-order";
    public static final String PAID_NOT_CREDITED = "pay-received-not-credited";
    public static final String CREDITED_WITHOUT_PAY = "credit-without-pay";

    private final BizReconcileMapper reconcileMapper;
    private final MeterRegistry meterRegistry;

    private final Map<String, AtomicLong> lastCounts = new java.util.concurrent.ConcurrentHashMap<>();

    @Value("${cabinet.scheduler.reconcile-batch-size:500}")
    private int batchSize;

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

    public List<String> hangingDepositOrderNos(Long tenantId, int limit) {
        return reconcileMapper.findHangingDepositOrderNos(tenantId, limit);
    }
}
