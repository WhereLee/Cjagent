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
public class CommandSweepHandler implements TaskHandler {

    private static final Logger log = LoggerFactory.getLogger(CommandSweepHandler.class);

    private final BizDeviceCommandMapper commandMapper;
    private final org.springframework.beans.factory.ObjectProvider<DelayTaskService> taskScheduler;

    @Value("${cabinet.scheduler.sweep-stale-minutes:2}")
    private long staleMinutes;

    @Value("${cabinet.scheduler.sweep-batch-size:50}")
    private int sweepBatchSize;

    CommandSweepHandler(BizDeviceCommandMapper commandMapper,
                        org.springframework.beans.factory.ObjectProvider<DelayTaskService> taskScheduler) {
        this.commandMapper = commandMapper;
        this.taskScheduler = taskScheduler;
    }

    @Override
    public TaskType type() {
        return TaskType.COMMAND_SWEEP;
    }

    @Override
    public boolean handle(BizDelayTask task) {
        DelayTaskService scheduler = taskScheduler.getIfAvailable();
        if (scheduler == null) {
            log.warn("没有调度器，指令扫街本轮什么也不能做");
            return true;
        }
        List<String> stuck = commandMapper.findStuckRequestIds(
                LocalDateTime.now().minusMinutes(staleMinutes), sweepBatchSize);
        LocalDateTime fireAt = LocalDateTime.now();
        for (String requestId : stuck) {
            scheduler.schedule(TaskType.COMMAND_RESCAN, requestId, task.getTenantId(), fireAt);
        }
        if (!stuck.isEmpty()) {
            log.warn("扫到卡住的指令 {} 条，已登记回扫（单轮上限 {}）",
                    stuck.size(), sweepBatchSize);
        }
        return true;
    }
}
