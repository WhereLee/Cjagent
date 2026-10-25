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
            orders.convergeDeviceOutcome(command.getOrderId(), command.getAction(), result.outcome());
        }
        return result.action() == CommandRescanService.RescanAction.REDISPATCHED
                && result.outcome() != null
                && result.outcome().state() == com.wherelee.cabinet.device.api.CommandState.TIMEOUT;
    }
}
