package com.wherelee.cabinet.application.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.funds.app.PricingPolicy;
import com.wherelee.cabinet.storage.api.FundPort;
import com.wherelee.cabinet.device.app.CommandRescanService;
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
public class CommandRescanHandler implements TaskHandler {

    private static final Logger log = LoggerFactory.getLogger(CommandRescanHandler.class);

    private final CommandRescanService rescans;
    private final BizDeviceCommandMapper commandMapper;
    private final StorageOrderService orders;

    CommandRescanHandler(CommandRescanService rescans, BizDeviceCommandMapper commandMapper,
                         StorageOrderService orders) {
        this.rescans = rescans;
        this.commandMapper = commandMapper;
        this.orders = orders;
    }

    @Override
    public TaskType type() {
        return TaskType.COMMAND_RESCAN;
    }

    @Override
    public boolean transactional() {
        return false;
    }

    @Override
    public boolean handle(BizDelayTask task) {
        CommandRescanService.RescanResult result = rescans.rescan(task.getBizKey());
        if (result.outcome() == null) {
            return false;
        }
        BizDeviceCommand command = commandMapper.selectByRequestId(task.getBizKey());
        if (command != null && command.getOrderId() != null) {
            // 订单收敛交给业务侧：它才知道“这个动作失败后件在谁手里”
            orders.convergeDeviceOutcome(command.getOrderId(), command.getAction(), result.outcome());
        }
        // 又超时且还有重试预算 → 让 worker 续排下一轮。不能指望 rescan 内部“顺手再登记”：
        // 此刻它自己的任务行还是 RUNNING，登记会走“不动”那一支，而 markDone 在后——
        // 不返 true 就等于只重试一次就断链（本刀实测）。
        return result.action() == CommandRescanService.RescanAction.REDISPATCHED
                && result.outcome() != null
                && result.outcome().state() == com.wherelee.cabinet.device.api.CommandState.TIMEOUT;
    }
}

/**
 * 指令扫街：把“卡在中间态”的指令重新推上回扫通道。
 *
 * <p>逐条登记只能盖住“超时”这一种；<b>下发写了、收敛还没跑进程就被杀</b>时，
 * 没有任何人会再提这条指令——没有这一层，第 11 刀拆开事务的代价就没人付。
 * 不直接重发而是登记 COMMAND_RESCAN：幂等键是 (类型, requestId)，重复扫到也不会堆任务，
 * 而收敛只有一处实现。
 */
