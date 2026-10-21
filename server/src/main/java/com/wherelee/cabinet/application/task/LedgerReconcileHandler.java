package com.wherelee.cabinet.application.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.funds.app.PricingPolicy;
import com.wherelee.cabinet.storage.api.FundPort;
import com.wherelee.cabinet.device.app.DeviceCommandService;
import com.wherelee.cabinet.funds.app.ReconcileReport;
import com.wherelee.cabinet.funds.app.ReconcileService;
import com.wherelee.cabinet.locker.api.SlotPort;
import com.wherelee.cabinet.locker.app.SlotCandidateQuery;
import com.wherelee.cabinet.storage.app.StorageOrderService;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.locker.domain.BizCabinet;
import com.wherelee.cabinet.locker.domain.BizCompartment;
import com.wherelee.cabinet.domain.entity.BizDelayTask;
import com.wherelee.cabinet.funds.domain.BizDeposit;
import com.wherelee.cabinet.device.domain.BizDeviceCommand;
import com.wherelee.cabinet.device.domain.BizFaultEvent;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.funds.api.DepositStatus;
import com.wherelee.cabinet.device.api.FaultType;
import com.wherelee.cabinet.domain.enums.OrderStatus;
import com.wherelee.cabinet.locker.api.SlotStatus;
import com.wherelee.cabinet.locker.api.SizeType;
import com.wherelee.cabinet.domain.enums.TaskType;
import com.wherelee.cabinet.locker.infra.SlotPreDeductionService;
import com.wherelee.cabinet.locker.infra.BizCabinetMapper;
import com.wherelee.cabinet.locker.infra.BizCompartmentMapper;
import com.wherelee.cabinet.funds.infra.BizDepositMapper;
import com.wherelee.cabinet.device.infra.BizDeviceCommandMapper;
import com.wherelee.cabinet.device.infra.BizFaultEventMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import io.micrometer.core.instrument.MeterRegistry;
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
        // 任务自带的租户号而不是 TenantContext.current()：worker 已包上上下文，但周期任务的
        // bizKey 本来就是租户号，显式取它才能“这条任务负责哪个租户”说得清
        Long tenantId = Long.valueOf(task.getBizKey());
        ReconcileReport report = reconcile.reconcile(tenantId);
        log.info("对账批次完成 tenant={} 扫描账户={} 差异项={}", report.tenantId(),
                report.accountsScanned(), report.failedChecks());

        // 只报告不够：差异必须有人接。“单已终态而押金未退”这组登记重试退还，
        // 上限一条批次（剩下的下一轮再接），否则一轮对账能写出万个任务把表埋了
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

/**
 * 设备指令超时回扫：把“下发与收敛不是一个事务”留下的现场收掉。
 *
 * <p><b>{@link #transactional()} 必须为 false</b>：回扫重试里也要等回执（最长 2.5 秒），
 * 外层一开事务就把第 11 刀“等回执不持事务”的拆法重新合上了——那正是第 9 刀压测量出来的瓶颈。
 * 代价显式承担：本 handler 每一步都靠条件更新/幂等键，重跑一遍不会多开门。
 */
