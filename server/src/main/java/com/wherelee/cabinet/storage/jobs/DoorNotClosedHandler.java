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
        // 到顶就停：再盯下去也不会多收一分，继续自转只会刷日志。
        // 停看管不等于丢现场：格口的 DOOR_OPEN 异常还在，对账会把未关门数计成指标。
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

/**
 * 逾期未取：转 EXPIRED（仍非终态，用户随时可来取），并在“再盯下去也不会多收一分”时交人工。
 *
 * <p>不自己“再排一轮”：那会把 handler 变成调度器的依赖（构成循环）。返回 true 让 worker 续排。
 *
 * <p><b为什么要收口</b>：“每小时续排一次，直到终态”在没有终态的单（用户把件忘了、手机丢了）上
 * 就是一张永远没人接的工单。两个出口任一命中就停：
 * ① 快照里的总封顶已到顶（再放也不收钱了）；② 逾期满 stranded-days（封顶配成了不限时的硬兑底）。
 * 停看管不等于丢现场：对账作业会把滞留单计成指标（永远能数出来），后台工单是第 14 刀。
 */
