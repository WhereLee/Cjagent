package com.wherelee.cabinet.application.task;

import com.wherelee.cabinet.notify.api.NoticePort;
import com.wherelee.cabinet.domain.entity.BizDelayTask;
import com.wherelee.cabinet.domain.entity.BizWorkOrder;
import com.wherelee.cabinet.domain.enums.TaskType;
import com.wherelee.cabinet.domain.enums.WorkOrderStatus;
import com.wherelee.cabinet.infrastructure.mapper.BizWorkOrderMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 工单 SLA 到点：响应时限与解决时限各一个任务。
 *
 * <p>为什么用延迟任务而不是扫表：SLA 到点是“某一刻必须响”的事件，
 * 扫表会让响应时间取决于轮询间隔，工单一多还会把连接池打满。
 *
 * <p>两个 handler 都只做一件事：把“超时”变成一条待办（{@code biz_notice} 的 PHONE_CALL 频道，
 * 与“必须打电话”同一张清单，业务员不用再记一个地方）。<b>不改工单状态、不自动关闭</b>——
 * 超时是催促，不是结论。
 */
public final class WorkOrderSlaHandlers {

    private WorkOrderSlaHandlers() {
    }

    /** 响应时限：认领即响应；已响应或暂停中都不再发。 */
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
            notices.emit(order.getTenantId(), NoticePort.CHANNEL_PHONE, "SLA_RESPONSE",
                    "WO" + order.getId() + ":SLA:RESP",
                    "工单 " + order.getId() + " 响应超时（" + order.getPriority() + "）："
                            + order.getTitle(),
                    order.getCustomerId(), order.getId(), order.getOrderNo());
            return false;
        }
    }

    /** 解决时限：已关闭或暂停中都不再发；到点只提醒，不替人下结论。 */
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
            notices.emit(order.getTenantId(), NoticePort.CHANNEL_PHONE, "SLA_RESOLVE",
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
