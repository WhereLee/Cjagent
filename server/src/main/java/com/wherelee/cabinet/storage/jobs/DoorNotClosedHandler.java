package com.wherelee.cabinet.storage.jobs;

import com.wherelee.cabinet.infrastructure.task.TaskOrders;
import com.wherelee.cabinet.platform.scheduling.TaskHandler;
import com.wherelee.cabinet.storage.api.FundPort;
import com.wherelee.cabinet.locker.api.SlotPort;
import com.wherelee.cabinet.storage.api.StorageOrderCommandPort;
import com.wherelee.cabinet.domain.entity.BizDelayTask;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.device.api.FaultType;
import com.wherelee.cabinet.domain.enums.TaskType;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;

@Component
public class DoorNotClosedHandler implements TaskHandler {

    private static final Logger log = LoggerFactory.getLogger(DoorNotClosedHandler.class);

    private final BizStorageOrderMapper orderMapper;
    private final com.wherelee.cabinet.device.api.FaultPort faults;
    private final StorageOrderCommandPort orders;
    private final SlotPort states;
    private final com.wherelee.cabinet.storage.api.FundPort pricing;

    DoorNotClosedHandler(BizStorageOrderMapper orderMapper, com.wherelee.cabinet.device.api.FaultPort faults,
                         StorageOrderCommandPort orders, SlotPort states, com.wherelee.cabinet.storage.api.FundPort pricing) {
        this.orderMapper = orderMapper;
        this.faults = faults;
        this.orders = orders;
        this.states = states;
        this.pricing = pricing;
    }

    @Override
    public TaskType type() {
        return TaskType.DOOR_NOT_CLOSED;
    }

    @Override
    public boolean transactional() {
        return false;
    }

    @Override
    public boolean handle(BizDelayTask task) {
        BizStorageOrder order = TaskOrders.find(orderMapper, task.getBizKey());
        if (order == null || order.getStatus().isTerminal()) {
            return false;
        }
        if (order.getStartedAt() != null && pricing.isCapped(order.getPricingSnapshot(),
                java.time.Duration.between(order.getStartedAt(), LocalDateTime.now()).toMinutes())) {
            recordFault(order, "柜门长时间未关且计费已到顶，转对账与运维清单");
            return false;
        }
        boolean remindAgain = orders.doorWatchTick(order.getOrderNo(),
                states.sense(order.getCabinetId(), order.getSlotId()));
        if (remindAgain) {
            recordFault(order, "柜门仍未关闭：已开始计费，直到关门或用户远程结束");
        }
        return remindAgain;
    }

    private void recordFault(BizStorageOrder order, String reason) {
        faults.record(order.getCabinetId(), order.getSlotId(), FaultType.Action.ALARM, 0, reason);
    }
}
