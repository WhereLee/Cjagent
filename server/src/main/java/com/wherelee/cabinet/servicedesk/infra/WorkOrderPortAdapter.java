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
