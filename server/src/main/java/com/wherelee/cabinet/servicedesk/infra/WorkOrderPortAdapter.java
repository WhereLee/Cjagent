package com.wherelee.cabinet.servicedesk.infra;

import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.locker.api.CompartmentAnomaly;
import com.wherelee.cabinet.servicedesk.api.WorkOrderPort;
import com.wherelee.cabinet.servicedesk.api.WorkOrderType;
import com.wherelee.cabinet.servicedesk.app.WorkOrderIntake;
import com.wherelee.cabinet.servicedesk.app.WorkOrderService;
import com.wherelee.cabinet.servicedesk.domain.BizWorkOrder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工单端口适配器：把格口状态机要的那几个动作翻译成工单服务的方法。
 *
 * <p>为什么不直接让格口状态机注入 WorkOrderService：那个类 58KB、方法几十个，
 * 一旦交出去，“顺手在这里改个工单字段”这种事迟早会发生。适配器把可见面压到七个动作，
 * 也把“格口用到工单”这件事写在了明面上。
 */
@Component
public class WorkOrderPortAdapter implements WorkOrderPort {

    private final WorkOrderIntake intake;
    private final WorkOrderService workOrders;
    private final BizWorkOrderMapper workOrderMapper;

    public WorkOrderPortAdapter(WorkOrderIntake intake, WorkOrderService workOrders,
                                BizWorkOrderMapper workOrderMapper) {
        this.intake = intake;
        this.workOrders = workOrders;
        this.workOrderMapper = workOrderMapper;
    }

    @Override
    public void openDeviceFault(BizStorageOrder order, boolean urgent, String detection, String title,
                                LocalDateTime dueAt) {
        intake.onOrderEvent(order, WorkOrderType.DEVICE_FAULT, urgent, detection, title, dueAt);
    }

    @Override
    public List<WorkOrderBrief> byStorageId(Long storageOrderId, int limit) {
        if (storageOrderId == null) {
            return List.of();
        }
        return workOrderMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<BizWorkOrder>lambdaQuery()
                        .eq(BizWorkOrder::getStorageId, storageOrderId)
                        .orderByDesc(BizWorkOrder::getCreateTime)
                        .last("limit " + Math.max(1, Math.min(limit, 100))))
                .stream()
                .map(w -> new WorkOrderBrief(w.getId(), name(w.getStatus()), name(w.getCategory()),
                        name(w.getPriority()), name(w.getCloseReason()), w.getTitle(), w.getCreateTime(),
                        name(w.getType()), w.getResolveDueAt()))
                .toList();
    }

    @Override
    public List<WorkOrderBrief> openOfCustomer(Long customerId, String orderNo, int limit) {
        if (customerId == null) {
            return List.of();
        }
        var query = com.baomidou.mybatisplus.core.toolkit.Wrappers.<BizWorkOrder>lambdaQuery()
                .eq(BizWorkOrder::getCustomerId, customerId)
                .eq(BizWorkOrder::getActiveFlag, 1)
                .orderByDesc(BizWorkOrder::getCreateTime)
                .last("limit " + Math.max(1, Math.min(limit, 50)));
        if (orderNo != null && !orderNo.isBlank()) {
            query.eq(BizWorkOrder::getOrderNo, orderNo);
        }
        return workOrderMapper.selectList(query).stream()
                .map(w -> new WorkOrderBrief(w.getId(), name(w.getStatus()), name(w.getCategory()),
                        name(w.getPriority()), name(w.getCloseReason()), w.getTitle(), w.getCreateTime(),
                        name(w.getType()), w.getResolveDueAt()))
                .toList();
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    @Override
    public void openForAnomaly(Long slotId, CompartmentAnomaly anomaly, String reason) {
        intake.onAnomaly(slotId, anomaly, reason);
    }

    @Override
    public int remindedTimes(Long slotId, WorkOrderType type) {
        return workOrders.remindedTimes(slotId, type);
    }

    @Override
    public Long openIdOf(Long slotId, WorkOrderType type) {
        return workOrders.openIdOf(slotId, type);
    }

    @Override
    public void remind(Long slotId, WorkOrderType type, String message) {
        workOrders.remind(slotId, type, message);
    }

    @Override
    public void escalate(Long slotId, WorkOrderType type, String reason) {
        workOrders.escalate(slotId, type, reason);
    }

    @Override
    public void noteStep(Long slotId, WorkOrderType type, String step, String note) {
        workOrders.noteStep(slotId, type, step, note);
    }

    @Override
    public void autoClose(Long slotId, WorkOrderType type) {
        workOrders.autoClose(slotId, type);
    }
}
