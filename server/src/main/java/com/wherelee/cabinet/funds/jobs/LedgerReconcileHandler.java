package com.wherelee.cabinet.funds.jobs;

import com.wherelee.cabinet.platform.scheduling.TaskHandler;
import com.wherelee.cabinet.platform.scheduling.DelayTaskService;
import com.wherelee.cabinet.funds.app.ReconcileReport;
import com.wherelee.cabinet.funds.app.ReconcileService;
import com.wherelee.cabinet.domain.entity.BizDelayTask;
import com.wherelee.cabinet.domain.enums.TaskType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.List;

@Component
public class LedgerReconcileHandler implements TaskHandler {

    private static final Logger log = LoggerFactory.getLogger(LedgerReconcileHandler.class);

    private final ReconcileService reconcile;
    private final org.springframework.beans.factory.ObjectProvider<DelayTaskService> taskScheduler;

    @Value("${cabinet.scheduler.deposit-sweep-limit:200}")
    private int depositSweepLimit;

    LedgerReconcileHandler(ReconcileService reconcile,
                           org.springframework.beans.factory.ObjectProvider<DelayTaskService> taskScheduler) {
        this.reconcile = reconcile;
        this.taskScheduler = taskScheduler;
    }

    @Override
    public TaskType type() {
        return TaskType.LEDGER_RECONCILE;
    }

    @Override
    public boolean handle(BizDelayTask task) {
        Long tenantId = Long.valueOf(task.getBizKey());
        ReconcileReport report = reconcile.reconcile(tenantId);
        log.info("对账批次完成 tenant={} 扫描账户={} 差异项={}", report.tenantId(),
                report.accountsScanned(), report.failedChecks());

        List<String> hanging = reconcile.hangingDepositOrderNos(tenantId, depositSweepLimit);
        DelayTaskService scheduler = taskScheduler.getIfAvailable();
        if (scheduler != null && !hanging.isEmpty()) {
            LocalDateTime fireAt = LocalDateTime.now().plusMinutes(1);
            for (String orderNo : hanging) {
                scheduler.schedule(TaskType.DEPOSIT_REFUND, orderNo, tenantId, fireAt);
            }
            log.warn("悬挂押金已登记退还重试 tenant={} 条数={}", tenantId, hanging.size());
        }
        return true;
    }
}
