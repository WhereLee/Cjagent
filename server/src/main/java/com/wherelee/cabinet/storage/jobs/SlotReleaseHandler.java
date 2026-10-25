package com.wherelee.cabinet.storage.jobs;

import com.wherelee.cabinet.infrastructure.task.TaskOrders;
import com.wherelee.cabinet.platform.scheduling.TaskHandler;
import com.wherelee.cabinet.locker.api.SlotPort;
import com.wherelee.cabinet.device.api.CommandPort;
import com.wherelee.cabinet.storage.api.StorageOrderCommandPort;
import com.wherelee.cabinet.domain.entity.BizDelayTask;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.OrderStatus;
import com.wherelee.cabinet.domain.enums.TaskType;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SlotReleaseHandler implements TaskHandler {

    private static final Logger log = LoggerFactory.getLogger(SlotReleaseHandler.class);

    private final BizStorageOrderMapper orderMapper;
    private final StorageOrderCommandPort orders;
    private final CommandPort deviceCommands;
    private final com.wherelee.cabinet.locker.api.SlotPort states;

    SlotReleaseHandler(BizStorageOrderMapper orderMapper, StorageOrderCommandPort orders,
                       CommandPort deviceCommands,
                       com.wherelee.cabinet.locker.api.SlotPort states) {
        this.orderMapper = orderMapper;
        this.orders = orders;
        this.deviceCommands = deviceCommands;
        this.states = states;
    }

    @Override
    public TaskType type() {
        return TaskType.SLOT_RELEASE;
    }

    private boolean anyOpenSucceeded(Long orderId) {
        return deviceCommands.anyOpenSucceeded(orderId);
    }

    @Override
    public boolean handle(BizDelayTask task) {
        BizStorageOrder order = TaskOrders.find(orderMapper, task.getBizKey());
        if (order == null) {
            log.info("超时释放跳过：订单不存在 orderNo={}", task.getBizKey());
            return false;
        }
        if (order.getStatus() != OrderStatus.RESERVED) {
            log.info("超时释放跳过：当前状态 {}", order.getStatus());
            return false;
        }
        if (anyOpenSucceeded(order.getId())) {
            states.markContentUnverified(order.getSlotId(),
                    "待收敛回收：开门指令已成功但订单仍停在待开门，需现场确认柜内是否有物");
            log.warn("待收敛回收：门开过但状态未推进，改为标待确认而不会退回格口 orderNo={} slotId={}",
                    order.getOrderNo(), order.getSlotId());
            return false;
        }
        if (order.getToleranceUntil() != null) {
            log.info("超时释放跳过：该单已开过门，改由门未关/逾期看管接手 orderNo={}", order.getOrderNo());
            return false;
        }
        orders.cancel(order.getCustomerId(), order.getOrderNo());
        log.info("超时未投件，已自动释放 orderNo={} slotId={}", order.getOrderNo(), order.getSlotId());
        return false;
    }
}
