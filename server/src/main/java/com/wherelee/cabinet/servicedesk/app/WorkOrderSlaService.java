package com.wherelee.cabinet.servicedesk.app;

import com.wherelee.cabinet.notify.api.NoticeChannel;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.platform.scheduling.DelayTaskService;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.enums.TaskType;
import com.wherelee.cabinet.notify.api.NoticePort;
import com.wherelee.cabinet.servicedesk.api.WorkOrderType;
import com.wherelee.cabinet.servicedesk.domain.BizWorkOrder;
import com.wherelee.cabinet.servicedesk.domain.BizWorkOrderEvent;
import com.wherelee.cabinet.servicedesk.infra.BizWorkOrderEventMapper;
import com.wherelee.cabinet.servicedesk.infra.BizWorkOrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
public class WorkOrderSlaService {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderSlaService.class);

    private final WorkOrderJournal journal;
    private final BizWorkOrderMapper orders;
    private final BizWorkOrderEventMapper events;
    private final NoticePort notices;
    private final ObjectProvider<DelayTaskService> schedulerProvider;

    public WorkOrderSlaService(WorkOrderJournal journal, BizWorkOrderMapper orders,
                               BizWorkOrderEventMapper events, NoticePort notices,
                               ObjectProvider<DelayTaskService> schedulerProvider) {
        this.journal = journal;
        this.orders = orders;
        this.events = events;
        this.notices = notices;
        this.schedulerProvider = schedulerProvider;
    }

    @Transactional
    public void pause(Long workOrderId, Long operatorId) {
        pause(workOrderId, operatorId, null);
    }

    @Transactional
    public void pause(Long workOrderId, Long operatorId, com.wherelee.cabinet.servicedesk.api.SlaPauseReason reason) {
        BizWorkOrder order = journal.require(workOrderId);
        if (!journal.isOpen(order)) {
            throw new BizException(ResultCode.BIZ_ERROR, "工单已关闭，不能暂停 SLA");
        }
        if (order.getSlaPausedAt() != null) {
            if (reason != null && !reason.name().equals(order.getSlaPauseReason())) {
                orders.update(null, Wrappers.<BizWorkOrder>lambdaUpdate()
                        .eq(BizWorkOrder::getId, workOrderId)
                        .set(BizWorkOrder::getSlaPauseReason, reason.name()));
                journal.note(order, "SLA_PAUSE_REASON", operatorId, "暂停原因更新为等待用户");
            }
            return;
        }
        orders.update(null, Wrappers.<BizWorkOrder>lambdaUpdate()
                .eq(BizWorkOrder::getId, workOrderId)
                .isNull(BizWorkOrder::getSlaPausedAt)
                .set(BizWorkOrder::getSlaPausedAt, LocalDateTime.now())
                .set(BizWorkOrder::getSlaPauseReason, reason == null ? null : reason.name()));
        cancel(order);
        journal.note(order, "SLA_PAUSED", operatorId,
                "SLA 已暂停（暂停时长不计入时限）" + (reason == null ? "" : "；原因：等待用户"));
    }

    @Transactional
    public void resume(Long workOrderId, Long operatorId) {
        BizWorkOrder order = journal.require(workOrderId);
        if (order.getSlaPausedAt() == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        long pausedMinutes = Math.max(0, Duration.between(order.getSlaPausedAt(), now).toMinutes());
        LocalDateTime responseDue = order.getResponseDueAt() == null
                ? null : order.getResponseDueAt().plusMinutes(pausedMinutes);
        LocalDateTime resolveDue = order.getResolveDueAt() == null
                ? null : order.getResolveDueAt().plusMinutes(pausedMinutes);
        int accumulated = (order.getSlaPausedMinutes() == null ? 0 : order.getSlaPausedMinutes())
                + (int) pausedMinutes;
        if (orders.update(null, Wrappers.<BizWorkOrder>lambdaUpdate()
                .eq(BizWorkOrder::getId, workOrderId)
                .isNotNull(BizWorkOrder::getSlaPausedAt)
                .set(BizWorkOrder::getSlaPausedAt, null)
                .set(BizWorkOrder::getSlaPauseReason, null)
                .set(BizWorkOrder::getSlaPausedMinutes, accumulated)
                .set(BizWorkOrder::getResponseDueAt, responseDue)
                .set(BizWorkOrder::getResolveDueAt, resolveDue)
                .set(BizWorkOrder::getDueAt, resolveDue)) == 0) {
            log.info("SLA 恢复被并发抢先或工单已关闭，本次不重复补时限 workOrderId={}", workOrderId);
            return;
        }
        order.setSlaPausedAt(null);
        order.setSlaPauseReason(null);
        order.setResponseDueAt(responseDue);
        order.setResolveDueAt(resolveDue);
        schedule(order);
        journal.note(order, "SLA_RESUMED", operatorId, "SLA 已恢复：暂停 " + pausedMinutes + " 分钟已补回时限");
    }

    public void schedule(BizWorkOrder order) {
        if (order == null || order.getId() == null || order.getTenantId() == null || !journal.isOpen(order)) {
            return;
        }
        String bizKey = String.valueOf(order.getId());
        if (order.getFirstResponseAt() == null && order.getResponseDueAt() != null) {
            scheduler().schedule(TaskType.WORK_ORDER_RESPONSE_SLA, bizKey,
                    order.getTenantId(), order.getResponseDueAt());
        }
        if (order.getResolveDueAt() != null) {
            scheduler().schedule(TaskType.WORK_ORDER_RESOLVE_SLA, bizKey,
                    order.getTenantId(), order.getResolveDueAt());
        }
    }

    public void cancel(BizWorkOrder order) {
        String bizKey = String.valueOf(order.getId());
        scheduler().cancel(TaskType.WORK_ORDER_RESPONSE_SLA, bizKey, order.getTenantId());
        scheduler().cancel(TaskType.WORK_ORDER_RESOLVE_SLA, bizKey, order.getTenantId());
    }

    public void cancelResponse(BizWorkOrder order) {
        scheduler().cancel(TaskType.WORK_ORDER_RESPONSE_SLA, String.valueOf(order.getId()),
                order.getTenantId());
    }

    private DelayTaskService scheduler() {
        return schedulerProvider.getObject();
    }

    @Transactional
    public boolean remind(Long slotId, WorkOrderType type, String detail) {
        BizWorkOrder open = journal.findOpen(type, slotId);
        if (open == null) {
            return false;
        }
        journal.note(open, "REMINDER", null, detail);
        return true;
    }

    @Transactional
    public boolean escalate(Long slotId, WorkOrderType type, String detail) {
        if (orders.escalateBySlot(slotId, type.name()) == 0) {
            return false;
        }
        BizWorkOrder open = journal.findOpen(type, slotId);
        if (open == null) {
            return true;
        }
        journal.note(open, "ESCALATED", null, detail);
        String phone = journal.readSnapshot(open, "customerPhone");
        String where = (open.getSlotNo() == null ? "格口" : "格口 " + open.getSlotNo())
                + "（" + open.getTitle() + "）";
        notices.emit(open.getTenantId(), NoticeChannel.PHONE_CALL,
                type.name(), "WO" + open.getId() + ":PHONE",
                "必须电话联系客户 " + (phone == null ? "（无电话记录）" : phone) + "：" + where + " — " + detail,
                open.getCustomerId(), open.getId(), open.getOrderNo());
        notices.emit(open.getTenantId(), NoticeChannel.SYSTEM_MESSAGE,
                type.name(), "WO" + open.getId() + ":MSG",
                "你的暂存柜" + where + " 需要处理：" + detail + "。请尽快关门或取走物品并结束订单。",
                open.getCustomerId(), open.getId(), open.getOrderNo());
        log.warn("工单升级紧急 slotId={} type={} {}", slotId, type, detail);
        return true;
    }

    public int remindedTimes(Long slotId, WorkOrderType type) {
        BizWorkOrder open = journal.findOpen(type, slotId);
        if (open == null) {
            return 0;
        }
        return Math.toIntExact(events.selectCount(Wrappers.<BizWorkOrderEvent>lambdaQuery()
                .eq(BizWorkOrderEvent::getWorkOrderId, open.getId())
                .eq(BizWorkOrderEvent::getEventType, "REMINDER")));
    }
}
