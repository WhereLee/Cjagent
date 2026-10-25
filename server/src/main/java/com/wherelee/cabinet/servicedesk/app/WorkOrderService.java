package com.wherelee.cabinet.servicedesk.app;

import com.wherelee.cabinet.common.api.PageQuery;
import com.wherelee.cabinet.common.api.PageResult;
import com.wherelee.cabinet.domain.enums.CloseReason;
import com.wherelee.cabinet.servicedesk.api.WorkOrderConsolePort;
import com.wherelee.cabinet.servicedesk.api.WorkOrderType;
import com.wherelee.cabinet.servicedesk.domain.BizWorkOrder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class WorkOrderService implements WorkOrderConsolePort {

    public static final String SIDE_ONLINE = "ONLINE";
    public static final String SIDE_FIELD = "FIELD";

    private final WorkOrderJournal journal;
    private final WorkOrderSlaService sla;
    private final WorkOrderQueryService queries;
    private final WorkOrderIntakeService intake;
    private final WorkOrderLifecycleService lifecycle;

    public WorkOrderService(WorkOrderJournal journal, WorkOrderSlaService sla,
                            WorkOrderQueryService queries, WorkOrderIntakeService intake,
                            WorkOrderLifecycleService lifecycle) {
        this.journal = journal;
        this.sla = sla;
        this.queries = queries;
        this.intake = intake;
        this.lifecycle = lifecycle;
    }

    public void pauseSla(Long workOrderId, Long operatorId) {
        sla.pause(workOrderId, operatorId);
    }

    @Override
    public void pauseSla(Long workOrderId, Long operatorId,
                         com.wherelee.cabinet.servicedesk.api.SlaPauseReason reason) {
        sla.pause(workOrderId, operatorId, reason);
    }

    public void resumeSla(Long workOrderId, Long operatorId) {
        sla.resume(workOrderId, operatorId);
    }

    public boolean remind(Long slotId, WorkOrderType type, String detail) {
        return sla.remind(slotId, type, detail);
    }

    public boolean escalate(Long slotId, WorkOrderType type, String detail) {
        return sla.escalate(slotId, type, detail);
    }

    public int remindedTimes(Long slotId, WorkOrderType type) {
        return sla.remindedTimes(slotId, type);
    }

    public Long open(NewOrder cmd) {
        return intake.open(cmd);
    }

    public Long open(NewOrderV2 cmd) {
        return intake.open(cmd);
    }

    public void triage(Long workOrderId) {
        intake.triage(workOrderId);
    }

    public void claim(Long workOrderId, Long operatorId) {
        lifecycle.claim(workOrderId, operatorId);
    }

    public Long resolve(Long workOrderId, Long operatorId, String resultNote, boolean needOffline) {
        return lifecycle.resolve(workOrderId, operatorId, resultNote, needOffline);
    }

    public Long resolve(Long workOrderId, Long operatorId, String resultNote, boolean needOffline,
                        CloseReason requestedCloseReason) {
        return lifecycle.resolve(workOrderId, operatorId, resultNote, needOffline, requestedCloseReason);
    }

    public int autoClose(Long slotId, WorkOrderType type) {
        return lifecycle.autoClose(slotId, type);
    }

    public void confirmResolved(Long workOrderId, Long customerId, String note) {
        lifecycle.confirmResolved(workOrderId, customerId, note);
    }

    public Long reopen(Long workOrderId, Long customerId, String reason) {
        return lifecycle.reopen(workOrderId, customerId, reason);
    }

    public void notePhoneCall(Long workOrderId, Long operatorId, String resultNote) {
        lifecycle.notePhoneCall(workOrderId, operatorId, resultNote);
    }

    public Long openFieldTwin(Long parentWorkOrderId, String reason) {
        return lifecycle.openFieldTwin(parentWorkOrderId, reason);
    }

    public Long openIdOf(Long slotId, WorkOrderType type) {
        BizWorkOrder open = journal.findOpen(type, slotId);
        return open == null ? null : open.getId();
    }

    public Long boundOrderId(Long slotId) {
        BizWorkOrder open = journal.findOpen(WorkOrderType.DOOR_NOT_CLOSED, slotId);
        return open == null ? null : open.getStorageId();
    }

    @Transactional
    public void noteStep(Long slotId, WorkOrderType type, String eventType, String detail) {
        BizWorkOrder open = journal.findOpen(type, slotId);
        if (open != null) {
            journal.note(open, eventType, null, detail);
        }
    }

    public java.util.List<EvidenceView> evidenceFor(Long workOrderId) {
        return queries.evidenceFor(workOrderId);
    }

    public PageResult<WorkOrderView> queue(String queueSide, boolean openOnly, PageQuery query) {
        return queries.queue(queueSide, openOnly, query);
    }

    public WorkOrderView one(Long workOrderId) {
        return queries.one(workOrderId);
    }

    public Family family(Long workOrderId) {
        return queries.family(workOrderId);
    }

    public List<SimilarCase> similarResolved(Long workOrderId, int limit) {
        return queries.similarResolved(workOrderId, limit);
    }

    public List<EventView> timeline(Long workOrderId) {
        return queries.timeline(workOrderId);
    }

    public List<CustomerView> myOrders(Long customerId, int limit) {
        return queries.myOrders(customerId, limit);
    }

    public CustomerView myOrder(Long workOrderId, Long customerId) {
        return queries.myOrder(workOrderId, customerId);
    }
}
