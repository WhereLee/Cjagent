package com.wherelee.cabinet.device.jobs;

import com.wherelee.cabinet.platform.scheduling.TaskHandler;
import com.wherelee.cabinet.device.app.CommandRescanService;
import com.wherelee.cabinet.storage.api.StorageOrderCommandPort;
import com.wherelee.cabinet.domain.entity.BizDelayTask;
import com.wherelee.cabinet.device.domain.BizDeviceCommand;
import com.wherelee.cabinet.domain.enums.TaskType;
import com.wherelee.cabinet.device.infra.BizDeviceCommandMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class CommandRescanHandler implements TaskHandler {

    private static final Logger log = LoggerFactory.getLogger(CommandRescanHandler.class);

    private final CommandRescanService rescans;
    private final BizDeviceCommandMapper commandMapper;
    private final StorageOrderCommandPort orders;

    CommandRescanHandler(CommandRescanService rescans, BizDeviceCommandMapper commandMapper,
                         StorageOrderCommandPort orders) {
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
