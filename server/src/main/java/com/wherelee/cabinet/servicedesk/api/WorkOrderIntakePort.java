package com.wherelee.cabinet.servicedesk.api;

import com.wherelee.cabinet.domain.entity.BizStorageOrder;

import java.time.LocalDateTime;

public interface WorkOrderIntakePort {

    Long onOrderEvent(BizStorageOrder order, WorkOrderType type, boolean urgent,
                      String detection, String title, LocalDateTime dueAt);

    Long onOrderEvent(BizStorageOrder order, WorkOrderType type, boolean urgent,
                      String detection, String title, LocalDateTime dueAt,
                      WorkOrderKind kind, WorkOrderSource source, WorkOrderIntent intent,
                      WorkOrderCategory category, WorkOrderPriority priority, String handoffJson,
                      String traceId);
}
