package com.wherelee.cabinet.servicedesk.app;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.servicedesk.api.ActorType;
import com.wherelee.cabinet.servicedesk.api.WorkOrderKind;
import com.wherelee.cabinet.servicedesk.api.WorkOrderStatus;
import com.wherelee.cabinet.servicedesk.api.WorkOrderType;
import com.wherelee.cabinet.servicedesk.domain.BizWorkOrder;
import com.wherelee.cabinet.servicedesk.domain.BizWorkOrderEvent;
import com.wherelee.cabinet.servicedesk.infra.BizWorkOrderEventMapper;
import com.wherelee.cabinet.servicedesk.infra.BizWorkOrderMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class WorkOrderJournal {

    private final BizWorkOrderMapper orders;
    private final BizWorkOrderEventMapper events;

    public WorkOrderJournal(BizWorkOrderMapper orders, BizWorkOrderEventMapper events) {
        this.orders = orders;
        this.events = events;
    }

    public BizWorkOrder require(Long id) {
        BizWorkOrder order = orders.selectById(id);
        if (order == null) {
            throw new BizException(ResultCode.BIZ_ERROR, "工单不存在：" + id);
        }
        return order;
    }

    public boolean isOpen(BizWorkOrder order) {
        return order.getStatus() == WorkOrderStatus.PENDING || order.getStatus() == WorkOrderStatus.CLAIMED;
    }

    public BizWorkOrder findOpenInQueue(WorkOrderType type, Long slotId, String queueSide) {
        return findOpenInQueue(WorkOrderKind.INCIDENT, type, slotId, queueSide);
    }

    public BizWorkOrder findOpenInQueue(WorkOrderKind kind, WorkOrderType type, Long slotId, String queueSide) {
        LambdaQueryWrapper<BizWorkOrder> query = Wrappers.<BizWorkOrder>lambdaQuery()
                .eq(BizWorkOrder::getKind, kind == null ? WorkOrderKind.INCIDENT : kind)
                .eq(BizWorkOrder::getSlotId, slotId)
                .eq(BizWorkOrder::getQueueSide, queueSide)
                .eq(BizWorkOrder::getActiveFlag, 1);
        if (type == null) {
            query.isNull(BizWorkOrder::getType);
        } else {
            query.eq(BizWorkOrder::getType, type);
        }
        return orders.selectOne(query);
    }

    public BizWorkOrder findOpen(WorkOrderKind kind, WorkOrderType type, Long slotId) {
        if (type == null || slotId == null) {
            return null;
        }
        return findOpenInQueue(kind, type, slotId, WorkOrderService.SIDE_ONLINE);
    }

    public BizWorkOrder findOpen(WorkOrderType type, Long slotId) {
        return findOpen(WorkOrderKind.INCIDENT, type, slotId);
    }

    public void note(BizWorkOrder order, String eventType, Long operatorId, String detail) {
        note(order, eventType, operatorId, detail,
                operatorId == null ? ActorType.SYSTEM.name() : ActorType.OPERATOR.name(), null);
    }

    public void note(BizWorkOrder order, String eventType, Long operatorId, String detail,
                     String actorType, String traceId) {
        BizWorkOrderEvent event = new BizWorkOrderEvent();
        event.setTenantId(order.getTenantId());
        event.setWorkOrderId(order.getId());
        event.setEventType(eventType);
        event.setActorType(actorType == null ? ActorType.SYSTEM.name() : actorType);
        event.setOperatorId(operatorId);
        event.setTraceId(traceId);
        event.setDetail(detail == null ? eventType : truncate(detail));
        event.setCreateTime(LocalDateTime.now());
        events.insert(event);
    }

    public String truncate(String text) {
        return text == null ? null : (text.length() <= 500 ? text : text.substring(0, 497) + "...");
    }

    public String readSnapshot(BizWorkOrder o, String key) {
        String json = o.getSnapshotJson();
        if (json == null) {
            return null;
        }
        int keyAt = json.indexOf(key);
        if (keyAt < 0) {
            return null;
        }
        int colon = json.indexOf(':', keyAt);
        int from = json.indexOf('"', colon + 1);
        if (colon < 0 || from < 0) {
            return null;
        }
        int to = json.indexOf('"', from + 1);
        return to < 0 ? null : json.substring(from + 1, to);
    }
}
