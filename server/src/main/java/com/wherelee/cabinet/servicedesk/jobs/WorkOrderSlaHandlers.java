package com.wherelee.cabinet.servicedesk.jobs;

import com.wherelee.cabinet.notify.api.NoticeChannel;
import com.wherelee.cabinet.platform.scheduling.TaskHandler;
import com.wherelee.cabinet.notify.api.NoticePort;
import com.wherelee.cabinet.domain.entity.BizDelayTask;
import com.wherelee.cabinet.servicedesk.domain.BizWorkOrder;
import com.wherelee.cabinet.domain.enums.TaskType;
import com.wherelee.cabinet.servicedesk.api.WorkOrderStatus;
import com.wherelee.cabinet.servicedesk.infra.BizWorkOrderMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

public final class WorkOrderSlaHandlers {

    private WorkOrderSlaHandlers() {
    }

    @Component
    public static class ResponseSlaHandler implements TaskHandler {

        private final BizWorkOrderMapper orders;
        private final NoticePort notices;

        ResponseSlaHandler(BizWorkOrderMapper orders, NoticePort notices) {
            this.orders = orders;
            this.notices = notices;
        }

        @Override
        public TaskType type() {
            return TaskType.WORK_ORDER_RESPONSE_SLA;
        }

        @Override
        public boolean handle(BizDelayTask task) {
            BizWorkOrder order = find(orders, task.getBizKey());
            if (order == null || !isOpen(order) || order.getSlaPausedAt() != null
                    || order.getFirstResponseAt() != null) {
                return false;
            }
            LocalDateTime due = order.getResponseDueAt();
            if (due == null || due.isAfter(LocalDateTime.now())) {
                return false;
            }
            notices.emit(order.getTenantId(), NoticeChannel.PHONE_CALL, "SLA_RESPONSE",
                    "WO" + order.getId() + ":SLA:RESP",
                    "工单 " + order.getId() + " 响应超时（" + order.getPriority() + "）："
                            + order.getTitle(),
                    order.getCustomerId(), order.getId(), order.getOrderNo());
            return false;
        }
    }

    @Component
    public static class ResolveSlaHandler implements TaskHandler {

        private final BizWorkOrderMapper orders;
        private final NoticePort notices;

        ResolveSlaHandler(BizWorkOrderMapper orders, NoticePort notices) {
            this.orders = orders;
            this.notices = notices;
        }

        @Override
        public TaskType type() {
            return TaskType.WORK_ORDER_RESOLVE_SLA;
        }

        @Override
        public boolean handle(BizDelayTask task) {
            BizWorkOrder order = find(orders, task.getBizKey());
            if (order == null || !isOpen(order) || order.getSlaPausedAt() != null) {
                return false;
            }
            LocalDateTime due = order.getResolveDueAt();
            if (due == null || due.isAfter(LocalDateTime.now())) {
                return false;
            }
            notices.emit(order.getTenantId(), NoticeChannel.PHONE_CALL, "SLA_RESOLVE",
                    "WO" + order.getId() + ":SLA:RESOLVE",
                    "工单 " + order.getId() + " 解决超时（" + order.getPriority() + "）："
                            + order.getTitle(),
                    order.getCustomerId(), order.getId(), order.getOrderNo());
            return false;
        }
    }

    private static BizWorkOrder find(BizWorkOrderMapper orders, String bizKey) {
        try {
            return orders.selectById(Long.valueOf(bizKey));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isOpen(BizWorkOrder order) {
        return order.getStatus() == WorkOrderStatus.PENDING || order.getStatus() == WorkOrderStatus.CLAIMED;
    }
}
