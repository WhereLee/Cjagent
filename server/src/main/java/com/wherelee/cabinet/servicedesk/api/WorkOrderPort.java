package com.wherelee.cabinet.servicedesk.api;

import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.locker.api.CompartmentAnomaly;

import java.time.LocalDateTime;
import java.util.List;

public interface WorkOrderPort {

    void openForAnomaly(Long slotId, CompartmentAnomaly anomaly, String reason);

    void openDeviceFault(BizStorageOrder order, boolean urgent, String detection, String title,
                         LocalDateTime dueAt);

    int remindedTimes(Long slotId, WorkOrderType type);

    Long openIdOf(Long slotId, WorkOrderType type);

    void remind(Long slotId, WorkOrderType type, String message);

    void escalate(Long slotId, WorkOrderType type, String reason);

    void noteStep(Long slotId, WorkOrderType type, String step, String note);

    void autoClose(Long slotId, WorkOrderType type);

    List<WorkOrderBrief> byStorageId(Long storageOrderId, int limit);

    List<WorkOrderBrief> openOfCustomer(Long customerId, String orderNo, int limit);

    record WorkOrderBrief(Long id, String status, String category, String priority, String closeReason,
                          String title, LocalDateTime createTime, String type, LocalDateTime resolveDueAt) {
    }
}
