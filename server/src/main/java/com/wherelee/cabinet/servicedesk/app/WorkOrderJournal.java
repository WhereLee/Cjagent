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

/**
 * 工单的“读取 + 留痕”两个共用动作。
 *
 * <p>为什么单独抽出来：生命周期、SLA、查询三处都要“按 id 取单不许为空”“按格口找未关闭的线上单”
 * “往事件流水里写一行”。这三件事各写一遍就会出现三种取单口径与三个事件写入者，
 * 而工单的全部追溯性都压在那条流水上。
 */
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

    /** 同 kind + type + slot + queue 的未关闭单；type 为空时按 IS NULL 查。 */
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

    /**
     * 当前未关闭的<b>线上</b>工单。
     *
     * <p>必须带 queue_side：V18 允许同一格口同类型同时存在 ONLINE 父单与 FIELD 子单，
     * 只按 slot+type 查会 selectOne 到两条并抛异常，把提醒、升级、阶梯全部打断。
     */
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

    /** 显式指定 actorType/traceId 的留痕：AI 动作不能混进人工或系统动作。 */
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

    /**
     * 从工单快照里读一个字符串字段。
     *
     * <p>用户改绑手机号、点位改了名之后，快照不能跟着变。
     *
     * <p><b>不能按 {@code "key":"} 拼精确位置</b>：MySQL 的 JSON 列存进去会被重排成
     * {@code "key": "..."}（冒号后多一个空格），按无空格的模式找就会永远返回 null。
     * 所以改成：定位键名 → 找第一个冒号 → 取冒号后第一个引号里的内容。
     */
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
