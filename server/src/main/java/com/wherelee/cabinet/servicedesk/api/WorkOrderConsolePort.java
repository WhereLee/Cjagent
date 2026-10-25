package com.wherelee.cabinet.servicedesk.api;

import com.wherelee.cabinet.common.api.PageQuery;
import com.wherelee.cabinet.common.api.PageResult;
import com.wherelee.cabinet.domain.enums.CloseReason;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface WorkOrderConsolePort {

    record EvidenceView(String moment, String source, String presence, BigDecimal confidence,
                        String aiLabel, String photoRef, String note, LocalDateTime at) {
    }

    record NewOrder(Long tenantId, WorkOrderType type, boolean urgent,
                    Long siteId, Long cabinetId, Long slotId, String slotNo,
                    String orderNo, Long storageId, Long customerId, String customerPhone,
                    String siteAddress, String cabinetNo, String title, String detection,
                    LocalDateTime dueAt) {
    }

    record NewOrderV2(Long tenantId, WorkOrderKind kind, WorkOrderType type, WorkOrderSource source,
                      WorkOrderIntent intent, WorkOrderCategory category, boolean urgent,
                      WorkOrderPriority priority, WorkOrderImpact impact, WorkOrderUrgency urgency,
                      String assigneeGroup, String queueSide,
                      Long siteId, Long cabinetId, Long slotId, String slotNo,
                      String orderNo, Long storageId, Long customerId, String customerPhone,
                      String siteAddress, String cabinetNo, String title, String detection,
                      LocalDateTime dueAt, LocalDateTime responseDueAt, LocalDateTime resolveDueAt,
                      String handoffJson, String traceId) {
    }

    record WorkOrderView(Long id, String kind, String source, String type, String intent, String category,
                String priority, String impact, String urgency, boolean urgent, String status,
                String queueSide, String assigneeGroup, String slotNo, String cabinetNo,
                String siteAddress, String orderNo, String customerPhone, String title,
                String detection, String resultNote, String closeReason, Long assigneeId,
                Long parentId, String parentResultNote, LocalDateTime dueAt,
                LocalDateTime responseDueAt, LocalDateTime resolveDueAt,
                LocalDateTime firstResponseAt, LocalDateTime closedAt, LocalDateTime createTime,
                String handoffJson, int reopenedCount,
                String slaPauseReason, Integer csatScore, String csatComment) {
    }

    record EventView(String eventType, String actorType, Long operatorId, String traceId,
                     String detail, LocalDateTime at) {
    }

    record Family(WorkOrderView self, WorkOrderView parent, List<WorkOrderView> children) {
    }

    record CustomerView(Long id, String orderNo, String title, String status, String category,
                        String priority, String closeReason, String resultNote, String slotNo,
                        String cabinetNo, String siteAddress, LocalDateTime responseDueAt,
                        LocalDateTime resolveDueAt, LocalDateTime closedAt, LocalDateTime createTime,
                        Long mergedIntoId, int reopenedCount, Integer csatScore) {
    }

    record SimilarCase(String title, String resultNote, String resolvedAt) {
    }

    Long open(NewOrder cmd);

    Long open(NewOrderV2 cmd);

    void triage(Long workOrderId);

    void claim(Long workOrderId, Long operatorId);

    Long resolve(Long workOrderId, Long operatorId, String resultNote, boolean needOffline);

    Long resolve(Long workOrderId, Long operatorId, String resultNote, boolean needOffline,
                 CloseReason requestedCloseReason);

    int autoClose(Long slotId, WorkOrderType type);

    void confirmResolved(Long workOrderId, Long customerId, String note);

    Long reopen(Long workOrderId, Long customerId, String reason);

    void notePhoneCall(Long workOrderId, Long operatorId, String resultNote);

    Long openFieldTwin(Long parentWorkOrderId, String reason);

    boolean remind(Long slotId, WorkOrderType type, String detail);

    boolean escalate(Long slotId, WorkOrderType type, String detail);

    int remindedTimes(Long slotId, WorkOrderType type);

    void pauseSla(Long workOrderId, Long operatorId);

    void pauseSla(Long workOrderId, Long operatorId, SlaPauseReason reason);

    void resumeSla(Long workOrderId, Long operatorId);

    Long openIdOf(Long slotId, WorkOrderType type);

    Long boundOrderId(Long slotId);

    void noteStep(Long slotId, WorkOrderType type, String eventType, String detail);

    PageResult<WorkOrderView> queue(String queueSide, boolean openOnly, PageQuery query);

    WorkOrderView one(Long workOrderId);

    Family family(Long workOrderId);

    List<SimilarCase> similarResolved(Long workOrderId, int limit);

    List<EventView> timeline(Long workOrderId);

    List<EvidenceView> evidenceFor(Long workOrderId);

    List<CustomerView> myOrders(Long customerId, int limit);

    CustomerView myOrder(Long workOrderId, Long customerId);
}
