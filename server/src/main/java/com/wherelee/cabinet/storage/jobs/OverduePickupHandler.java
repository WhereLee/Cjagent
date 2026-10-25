package com.wherelee.cabinet.storage.jobs;

import com.wherelee.cabinet.infrastructure.task.TaskOrders;
import com.wherelee.cabinet.platform.scheduling.TaskHandler;
import com.wherelee.cabinet.storage.api.FundPort;
import com.wherelee.cabinet.domain.entity.BizDelayTask;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.OrderStatus;
import com.wherelee.cabinet.domain.enums.TaskType;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;

@Component
public class OverduePickupHandler implements TaskHandler {

    private static final Logger log = LoggerFactory.getLogger(OverduePickupHandler.class);

    private final BizStorageOrderMapper orderMapper;
    private final com.wherelee.cabinet.storage.api.FundPort pricing;
    private final MeterRegistry meterRegistry;
    private final com.wherelee.cabinet.servicedesk.api.WorkOrderIntakePort intake;
    private final com.wherelee.cabinet.servicedesk.api.WorkOrderConsolePort workOrders;

    @Value("${cabinet.scheduler.stranded-days:7}")
    private long strandedDays;

    OverduePickupHandler(BizStorageOrderMapper orderMapper, com.wherelee.cabinet.storage.api.FundPort pricing,
                         MeterRegistry meterRegistry,
                         com.wherelee.cabinet.servicedesk.api.WorkOrderIntakePort intake,
                         com.wherelee.cabinet.servicedesk.api.WorkOrderConsolePort workOrders) {
        this.orderMapper = orderMapper;
        this.pricing = pricing;
        this.meterRegistry = meterRegistry;
        this.intake = intake;
        this.workOrders = workOrders;
    }

    @Override
    public TaskType type() {
        return TaskType.OVERDUE_PICKUP;
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
        if (order.getStatus() != OrderStatus.ACTIVE && order.getStatus() != OrderStatus.TEMP_OPEN
                && order.getStatus() != OrderStatus.EXPIRED) {
            return false;
        }

        LocalDateTime deadline = order.getExpectedFinishAt() != null
                ? order.getExpectedFinishAt() : order.getCreateTime();
        long overdueMinutes = Math.max(0L,
                java.time.Duration.between(deadline, LocalDateTime.now()).toMinutes());
        long heldMinutes = order.getStartedAt() == null ? overdueMinutes
                : Math.max(0L, java.time.Duration.between(order.getStartedAt(), LocalDateTime.now()).toMinutes());

        if (order.getStatus() == OrderStatus.ACTIVE || order.getStatus() == OrderStatus.TEMP_OPEN) {
            order.transitTo(OrderStatus.EXPIRED);
            if (orderMapper.updateById(order) == 0) {
                BizStorageOrder now = orderMapper.selectById(order.getId());
                log.info("逾期推进 EXPIRED 落空，订单已被并发处置，本轮不记指标也不开单 orderNo={} 现状={}",
                        order.getOrderNo(), now == null ? "已不存在" : now.getStatus());
                return true;
            }
            meterRegistry.counter("cabinet.order.overdue", "size", order.getSizeType().name()).increment();
            log.info("订单逾期转 EXPIRED orderNo={} 逾期点={} 已逾 {} 分钟", order.getOrderNo(),
                    order.getExpectedFinishAt(), overdueMinutes);
        }

        boolean capped = pricing.isCapped(order.getPricingSnapshot(), heldMinutes);
        if (capped || overdueMinutes >= strandedDays * 24L * 60L) {
            meterRegistry.counter("cabinet.order.stranded",
                    "reason", capped ? "price-capped" : "watch-expired").increment();
            log.error("订单滞留需人工处置 orderNo={} 已持有分钟={} 逾期分钟={} 封顶已到={} 盯了 {} 天",
                    order.getOrderNo(), heldMinutes, overdueMinutes, capped, strandedDays);
            return !openLongRunning(order, heldMinutes);
        }
        return true;
    }

    private boolean openLongRunning(BizStorageOrder order, long heldMinutes) {
        try {
            intake.onOrderEvent(order, com.wherelee.cabinet.servicedesk.api.WorkOrderType.LONG_RUNNING,
                    false, "计费已到封顶（最多 20 小时单价），需联系用户取件",
                    "超 48 小时未结束：需联系客户", java.time.LocalDateTime.now().plusHours(2));
            Long woId = workOrders.openIdOf(order.getSlotId(),
                    com.wherelee.cabinet.servicedesk.api.WorkOrderType.LONG_RUNNING);
            if (woId == null) {
                return false;
            }
            workOrders.escalate(order.getSlotId(),
                    com.wherelee.cabinet.servicedesk.api.WorkOrderType.LONG_RUNNING,
                    "订单超 48 小时未结束：必须联系客户取件或结单");
            if (heldMinutes >= 72L * 60L) {
                workOrders.openFieldTwin(woId, "用户超 72 小时未取件，需到场处理");
            }
            return true;
        } catch (RuntimeException e) {
            log.error("滞留工单开单失败，下一轮重试 orderNo={}", order.getOrderNo(), e);
            return false;
        }
    }
}
