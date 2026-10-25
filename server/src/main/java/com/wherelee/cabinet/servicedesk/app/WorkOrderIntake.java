package com.wherelee.cabinet.servicedesk.app;

import com.wherelee.cabinet.locker.api.CabinetSnapshot;
import com.wherelee.cabinet.locker.api.SlotQueryPort;
import com.wherelee.cabinet.locker.api.SlotSnapshot;
import com.wherelee.cabinet.locker.api.SiteSnapshot;
import com.wherelee.cabinet.identity.api.CustomerQueryPort;
import com.wherelee.cabinet.identity.api.CustomerSnapshot;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.locker.api.CompartmentAnomaly;
import com.wherelee.cabinet.locker.api.OnlineState;
import com.wherelee.cabinet.locker.api.Presence;
import com.wherelee.cabinet.servicedesk.api.WorkOrderCategory;
import com.wherelee.cabinet.servicedesk.api.WorkOrderImpact;
import com.wherelee.cabinet.servicedesk.api.WorkOrderIntent;
import com.wherelee.cabinet.servicedesk.api.WorkOrderIntakePort;
import com.wherelee.cabinet.servicedesk.api.WorkOrderConsolePort;
import com.wherelee.cabinet.servicedesk.api.WorkOrderKind;
import com.wherelee.cabinet.servicedesk.api.WorkOrderPriority;
import com.wherelee.cabinet.servicedesk.api.WorkOrderSource;
import com.wherelee.cabinet.servicedesk.api.WorkOrderType;
import com.wherelee.cabinet.servicedesk.api.WorkOrderUrgency;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class WorkOrderIntake implements WorkOrderIntakePort {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderIntake.class);

    private final WorkOrderService workOrders;
    private final SlotQueryPort slotQueries;
    private final CustomerQueryPort customers;
    private final BizStorageOrderMapper orderMapper;
    private final WorkOrderTriageService triage;
    private final WorkOrderSlaPolicy slaPolicy;

    public WorkOrderIntake(WorkOrderService workOrders, SlotQueryPort slotQueries,
                           CustomerQueryPort customers, BizStorageOrderMapper orderMapper,
                           WorkOrderTriageService triage, WorkOrderSlaPolicy slaPolicy) {
        this.workOrders = workOrders;
        this.slotQueries = slotQueries;
        this.customers = customers;
        this.orderMapper = orderMapper;
        this.triage = triage;
        this.slaPolicy = slaPolicy;
    }

    public void onAnomaly(Long slotId, CompartmentAnomaly anomaly, String reason) {
        SlotSnapshot slot = slotQueries.slot(slotId).orElse(null);
        if (slot == null) {
            log.warn("标异常却查不到格口，工单跳过 slotId={} anomaly={}", slotId, anomaly);
            return;
        }
        WorkOrderType type;
        boolean urgent;
        switch (anomaly) {
            case DOOR_OPEN -> {
                type = WorkOrderType.DOOR_NOT_CLOSED;
                urgent = Presence.PRESENT.name().equals(slot.presence());
            }
            case CONTENT_LEFT -> {
                type = WorkOrderType.ITEM_LEFT;
                urgent = true;
            }
            case CONTENT_UNVERIFIED -> {
                type = WorkOrderType.ITEM_LEFT;
                urgent = true;
            }
            case SENSOR_CONFLICT -> {
                type = WorkOrderType.DEVICE_FAULT;
                urgent = false;
            }
            default -> {
                return;
            }
        }

        CabinetSnapshot cabinet = slotQueries.cabinet(slot.cabinetId()).orElse(null);
        SiteSnapshot site = cabinet == null || cabinet.siteId() == null
                ? null : slotQueries.site(cabinet.siteId()).orElse(null);
        BizStorageOrder order = slot.currentOrderId() == null
                ? null : orderMapper.selectById(slot.currentOrderId());
        Long customerId = order != null ? order.getCustomerId() : slot.doorOpenedBy();
        CustomerSnapshot customer = customerId == null ? null : customers.byId(customerId).orElse(null);

        LocalDateTime now = LocalDateTime.now();
        WorkOrderPriority priority = urgent ? WorkOrderPriority.P1 : WorkOrderPriority.P3;
        LocalDateTime responseDue = now.plusMinutes(slaPolicy.responseMinutes(priority));
        LocalDateTime resolveDue = now.plusMinutes(slaPolicy.resolveMinutes(priority));
        workOrders.open(new WorkOrderConsolePort.NewOrderV2(
                slot.tenantId(), WorkOrderKind.INCIDENT, type, WorkOrderSource.DEVICE,
                intentOf(anomaly), categoryOf(type), urgent, priority,
                WorkOrderImpact.SINGLE,
                urgent ? WorkOrderUrgency.HIGH : WorkOrderUrgency.NORMAL,
                WorkOrderService.SIDE_ONLINE, WorkOrderService.SIDE_ONLINE,
                site == null ? null : site.id(), cabinet == null ? null : cabinet.id(),
                slot.id(), slot.slotNo(),
                order == null ? null : order.getOrderNo(), order == null ? null : order.getId(),
                customer == null ? null : customer.id(), customer == null ? null : customer.phone(),
                site == null ? "未知点位" : site.name(),
                cabinet == null ? "-" : cabinet.cabinetNo(),
                titleOf(anomaly, slot.slotNo(), reason),
                detection(slot),
                resolveDue, responseDue, resolveDue, null, null));
    }

    public void longRunningFor(Long slotId, String reason) {
        SlotSnapshot slot = slotId == null ? null : slotQueries.slot(slotId).orElse(null);
        BizStorageOrder order = slot == null || slot.currentOrderId() == null
                ? null : orderMapper.selectById(slot.currentOrderId());
        if (order == null) {
            log.warn("超封顶开单跳过：格口上没有活动单 slotId={}", slotId);
            return;
        }
        onOrderEvent(order, WorkOrderType.LONG_RUNNING, false, "计费已到 48 小时封顶", reason,
                java.time.LocalDateTime.now().plusHours(2));
    }

    @Override
    public Long onOrderEvent(BizStorageOrder order, WorkOrderType type, boolean urgent,
                             String detection, String title, LocalDateTime dueAt) {
        return onOrderEvent(order, type, urgent, detection, title, dueAt,
                WorkOrderKind.INCIDENT, WorkOrderSource.SYSTEM, null, null, null, null, null);
    }

    @Override
    public Long onOrderEvent(BizStorageOrder order, WorkOrderType type, boolean urgent,
                             String detection, String title, LocalDateTime dueAt,
                             WorkOrderKind kind, WorkOrderSource source, WorkOrderIntent intent,
                             WorkOrderCategory category, WorkOrderPriority priority, String handoffJson,
                             String traceId) {
        SlotSnapshot slot = order.getSlotId() == null ? null : slotQueries.slot(order.getSlotId()).orElse(null);
        CabinetSnapshot cabinet = slot == null || slot.cabinetId() == null
                ? null : slotQueries.cabinet(slot.cabinetId()).orElse(null);
        SiteSnapshot site = cabinet == null ? null : slotQueries.site(cabinet.siteId()).orElse(null);
        CustomerSnapshot customer = order.getCustomerId() == null
                ? null : customers.byId(order.getCustomerId()).orElse(null);
        WorkOrderKind actualKind = kind == null ? WorkOrderKind.INCIDENT : kind;
        boolean deviceOffline = cabinet != null && OnlineState.OFFLINE.name().equals(cabinet.onlineState());
        WorkOrderTriageService.Result triaged = actualKind == WorkOrderKind.REQUEST && intent != null
                ? triage.triage(intent, new WorkOrderTriageService.Facts(deviceOffline, false, false, urgent))
                : null;
        WorkOrderType resolvedType = triaged == null ? type : triaged.type();
        WorkOrderCategory resolvedCategory = triaged == null ? category : triaged.category();
        WorkOrderPriority resolvedPriority = triaged == null
                ? (priority == null ? (urgent ? WorkOrderPriority.P1 : WorkOrderPriority.P3) : priority)
                : triaged.priority();
        WorkOrderImpact resolvedImpact = triaged == null ? WorkOrderImpact.SINGLE : triaged.impact();
        WorkOrderUrgency resolvedUrgency = triaged == null
                ? (urgent ? WorkOrderUrgency.HIGH : WorkOrderUrgency.NORMAL) : triaged.urgency();
        String resolvedGroup = triaged == null ? WorkOrderService.SIDE_ONLINE : triaged.assigneeGroup();
        String resolvedQueue = triaged == null ? WorkOrderService.SIDE_ONLINE : triaged.queueSide();
        LocalDateTime responseDue = triaged == null ? dueAt
                : LocalDateTime.now().plusMinutes(triaged.responseMinutes());
        LocalDateTime resolveDue = triaged == null ? dueAt
                : LocalDateTime.now().plusMinutes(triaged.resolveMinutes());
        return workOrders.open(new WorkOrderConsolePort.NewOrderV2(
                order.getTenantId(), actualKind, resolvedType, source, intent, resolvedCategory, urgent,
                resolvedPriority, resolvedImpact, resolvedUrgency, resolvedGroup, resolvedQueue,
                site == null ? null : site.id(), cabinet == null ? null : cabinet.id(),
                order.getSlotId(), slot == null ? null : slot.slotNo(),
                order.getOrderNo(), order.getId(), order.getCustomerId(),
                customer == null ? null : customer.phone(),
                site == null ? "未知点位" : site.name(),
                cabinet == null ? "-" : cabinet.cabinetNo(),
                title, detection, resolveDue, responseDue, resolveDue, handoffJson, traceId));
    }

    private WorkOrderIntent intentOf(CompartmentAnomaly anomaly) {
        return switch (anomaly) {
            case DOOR_OPEN -> WorkOrderIntent.DOOR_NOT_CLOSE;
            case CONTENT_LEFT, CONTENT_UNVERIFIED -> WorkOrderIntent.ITEM_LEFT;
            case SENSOR_CONFLICT -> WorkOrderIntent.OTHER;
            default -> WorkOrderIntent.OTHER;
        };
    }

    private WorkOrderCategory categoryOf(WorkOrderType type) {
        return switch (type) {
            case DOOR_NOT_CLOSED -> WorkOrderCategory.DOOR_NOT_CLOSED;
            case ITEM_LEFT -> WorkOrderCategory.ITEM_LEFT;
            case DEVICE_FAULT -> WorkOrderCategory.DOOR_FAULT;
            case LONG_RUNNING -> WorkOrderCategory.UNSORTED;
        };
    }

    private String detection(SlotSnapshot slot) {
        return "门=" + (slot.doorOpen() ? "开着" : "已关")
                + "；柜内=" + presenceText(slot.presence())
                + "；异常=" + slot.anomaly();
    }

    private String presenceText(String presence) {
        if (presence == null) {
            return "未测";
        }
        return switch (Presence.valueOf(presence)) {
            case PRESENT -> "有物";
            case ABSENT -> "无物";
            case UNKNOWN -> "测不准";
        };
    }

    private String titleOf(CompartmentAnomaly anomaly, String slotNo, String reason) {
        String who = slotNo == null ? "格口" : "格口 " + slotNo;
        return switch (anomaly) {
            case DOOR_OPEN -> who + " 门未关：" + reason;
            case CONTENT_LEFT -> who + " 柜内有遗留物：" + reason;
            case CONTENT_UNVERIFIED -> who + " 无法确认已清空：" + reason;
            case SENSOR_CONFLICT -> who + " 传感器结论矛盾：" + reason;
            default -> who + " 异常：" + reason;
        };
    }
}
