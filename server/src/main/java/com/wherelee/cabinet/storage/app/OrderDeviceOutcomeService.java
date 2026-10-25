package com.wherelee.cabinet.storage.app;

import com.wherelee.cabinet.platform.scheduling.DelayTaskService;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.device.api.CommandAction;
import com.wherelee.cabinet.device.api.CommandOutcome;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.OrderStatus;
import com.wherelee.cabinet.domain.enums.TaskType;
import com.wherelee.cabinet.locker.api.Sensing;
import com.wherelee.cabinet.locker.api.SlotPort;
import com.wherelee.cabinet.locker.api.SlotQueryPort;
import com.wherelee.cabinet.locker.api.SlotSnapshot;
import com.wherelee.cabinet.storage.api.FundPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class OrderDeviceOutcomeService {

    private static final Logger log = LoggerFactory.getLogger(OrderDeviceOutcomeService.class);

    private final SlotPort states;
    private final SlotQueryPort slotQueries;
    private final FundPort funds;
    private final ObjectProvider<DelayTaskService> taskScheduler;

    @Value("${cabinet.door-watch.first-remind-minutes:3}")
    private long firstRemindMinutes;

    public OrderDeviceOutcomeService(SlotPort states, SlotQueryPort slotQueries, FundPort funds,
                                     ObjectProvider<DelayTaskService> taskScheduler) {
        this.states = states;
        this.slotQueries = slotQueries;
        this.funds = funds;
        this.taskScheduler = taskScheduler;
    }

    public void apply(BizStorageOrder order, CommandAction action, CommandOutcome outcome) {
        if (outcome.stale()) {
            return;
        }
        if (outcome.businessSuccess()) {
            switch (action) {
                case OPEN -> {
                    states.afterDoorOpened(order.getSlotId(), order.getCustomerId());
                    startBilling(order, LocalDateTime.now());
                    log.info("格口已开门并落定计费起点 orderNo={} slotId={} 起点={}",
                            order.getOrderNo(), order.getSlotId(), order.getStartedAt());
                    scheduleDoorWatch(order);
                }
                case OPEN_TEMP -> {
                    order.setTempOpenCount(order.getTempOpenCount() == null ? 1 : order.getTempOpenCount() + 1);
                    order.transitTo(OrderStatus.TEMP_OPEN);
                    states.afterDoorOpened(order.getSlotId(), order.getCustomerId());
                    scheduleDoorWatch(order);
                }
                case CLOSE_VERIFY -> closeVerified(order);
                default -> throw new BizException(ResultCode.PARAM_INVALID, "不支持的动作：" + action);
            }
            return;
        }

        switch (action) {
            case OPEN -> {
                if (order.getStatus() == OrderStatus.OPENING) {
                    order.transitTo(OrderStatus.RESERVED);
                }
                log.info("开柜未成功，已退回可重试态 orderNo={} fault={}", order.getOrderNo(), outcome.faultType());
            }
            case OPEN_TEMP -> {
                if (order.getStatus() == OrderStatus.TEMP_OPEN) {
                    order.transitTo(OrderStatus.ACTIVE);
                }
            }
            case CLOSE_VERIFY, FORCE_OPEN -> {
                states.markSensorConflict(order.getSlotId(),
                        "设备称关门成功但门磁不认（谎报或门磁卡住），需现场确认");
                log.warn("关门未被门磁确认，不推进订单状态 orderNo={} fault={}",
                        order.getOrderNo(), outcome.faultType());
            }
            default -> order.transitTo(OrderStatus.ABNORMAL);
        }
    }

    private void closeVerified(BizStorageOrder order) {
        applyCloseVerified(order, states.sense(order.getCabinetId(), order.getSlotId()));
    }

    public void applyCloseVerified(BizStorageOrder order, Sensing sensing) {
        if (!sensing.doorClosed()) {
            states.markSensorConflict(order.getSlotId(), "收到关门校验但门磁仍报开着，需现场确认");
            log.warn("关门校验未被门磁确认 orderNo={} slotId={}", order.getOrderNo(), order.getSlotId());
            return;
        }
        states.afterDoorClosed(order.getSlotId(), order.getCustomerId());
        if (order.getStatus() == OrderStatus.TEMP_OPEN) {
            order.transitTo(OrderStatus.ACTIVE);
        } else if (order.getStatus() == OrderStatus.OPENING || order.getStatus() == OrderStatus.RESERVED) {
            if (!states.occupy(order.getSlotId(), order.getId())) {
                throw new BizException(ResultCode.SYSTEM_ERROR,
                        "格口状态与订单不一致，需人工核对 slotId=" + order.getSlotId());
            }
            order.transitTo(OrderStatus.STORED);
            order.transitTo(OrderStatus.ACTIVE);
        }
        startBilling(order, LocalDateTime.now());
        states.tryAutoRecover(order.getSlotId(), sensing);
    }

    public void startBilling(BizStorageOrder order, LocalDateTime start) {
        if (order.getStartedAt() != null) {
            return;
        }
        order.setStartedAt(start);
        int capDays = funds.capDaysOf(order.getPricingSnapshot());
        order.setExpectedFinishAt(capDays > 0 ? start.plusDays(capDays) : null);
        var scheduler = taskScheduler.getIfAvailable();
        if (scheduler != null && order.getExpectedFinishAt() != null) {
            scheduler.schedule(TaskType.OVERDUE_PICKUP,
                    order.getOrderNo(), order.getTenantId(), order.getExpectedFinishAt());
        }
    }

    public boolean mayCloseVerify(BizStorageOrder order) {
        if (order.getStatus() == OrderStatus.OPENING || order.getStatus() == OrderStatus.TEMP_OPEN) {
            return true;
        }
        SlotSnapshot slot = slotQueries.slot(order.getSlotId()).orElse(null);
        return order.getStatus() == OrderStatus.ACTIVE && slot != null && slot.doorOpen();
    }

    public void scheduleDoorWatch(BizStorageOrder order) {
        LocalDateTime toleranceUntil = LocalDateTime.now().plusMinutes(toleranceOf(order));
        if (order.getToleranceUntil() == null) {
            order.setToleranceUntil(toleranceUntil);
        }
        var scheduler = taskScheduler.getIfAvailable();
        if (scheduler == null) {
            return;
        }
        scheduler.schedule(TaskType.DOOR_NOT_CLOSED,
                order.getOrderNo(), order.getTenantId(), order.getToleranceUntil());
    }

    private long toleranceOf(BizStorageOrder order) {
        long configured = funds.toleranceMinutesOf(order.getSiteId());
        return configured < 0 ? firstRemindMinutes : configured;
    }
}
