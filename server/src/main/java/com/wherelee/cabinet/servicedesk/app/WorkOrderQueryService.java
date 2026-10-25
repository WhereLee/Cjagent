package com.wherelee.cabinet.servicedesk.app;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wherelee.cabinet.common.api.PageQuery;
import com.wherelee.cabinet.common.api.PageResult;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.device.api.EvidencePort;
import com.wherelee.cabinet.platform.security.PhoneAccessAuditor;
import com.wherelee.cabinet.platform.security.PhoneVisibility;
import com.wherelee.cabinet.platform.web.PageSupport;
import com.wherelee.cabinet.servicedesk.api.WorkOrderConsolePort;
import com.wherelee.cabinet.servicedesk.api.WorkOrderStatus;
import com.wherelee.cabinet.servicedesk.domain.BizWorkOrder;
import com.wherelee.cabinet.servicedesk.domain.BizWorkOrderEvent;
import com.wherelee.cabinet.servicedesk.domain.BizWorkOrderCsat;
import com.wherelee.cabinet.servicedesk.infra.BizWorkOrderCsatMapper;
import com.wherelee.cabinet.servicedesk.infra.BizWorkOrderEventMapper;
import com.wherelee.cabinet.servicedesk.infra.BizWorkOrderMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class WorkOrderQueryService {

    private static final Set<String> QUEUE_SORTABLE = Set.of("createTime", "dueAt");

    private final BizWorkOrderMapper orders;
    private final BizWorkOrderEventMapper events;
    private final EvidencePort evidencePort;
    private final WorkOrderJournal journal;
    private final BizWorkOrderCsatMapper csats;
    private final PhoneAccessAuditor phoneAuditor;

    public WorkOrderQueryService(BizWorkOrderMapper orders, BizWorkOrderEventMapper events,
                                 EvidencePort evidencePort, WorkOrderJournal journal,
                                 BizWorkOrderCsatMapper csats, PhoneAccessAuditor phoneAuditor) {
        this.orders = orders;
        this.events = events;
        this.evidencePort = evidencePort;
        this.journal = journal;
        this.csats = csats;
        this.phoneAuditor = phoneAuditor;
    }

    public PageResult<WorkOrderConsolePort.WorkOrderView> queue(String queueSide, boolean openOnly, PageQuery query) {
        Page<BizWorkOrder> page = PageSupport.toPage(query, QUEUE_SORTABLE);
        return PageSupport.toResult(orders.selectPage(page, Wrappers.<BizWorkOrder>lambdaQuery()
                .eq(BizWorkOrder::getQueueSide, queueSide)
                .eq(BizWorkOrder::getDeleted, 0)
                .eq(openOnly, BizWorkOrder::getActiveFlag, 1)
                .orderByAsc(BizWorkOrder::getPriority)
                .orderByAsc(BizWorkOrder::getCreateTime)), this::toView);
    }

    public WorkOrderConsolePort.WorkOrderView one(Long workOrderId) {
        BizWorkOrder order = journal.require(workOrderId);
        String parentNote = null;
        if (order.getParentId() != null) {
            BizWorkOrder parent = orders.selectById(order.getParentId());
            parentNote = parent == null ? null : parent.getResultNote();
        }
        return toView(order, parentNote, csatOf(workOrderId));
    }

    public WorkOrderConsolePort.Family family(Long workOrderId) {
        BizWorkOrder order = journal.require(workOrderId);
        WorkOrderConsolePort.WorkOrderView self = toView(order, null, csatOf(workOrderId));
        WorkOrderConsolePort.WorkOrderView parent = order.getParentId() == null ? null : one(order.getParentId());
        List<WorkOrderConsolePort.WorkOrderView> children = orders.selectList(Wrappers.<BizWorkOrder>lambdaQuery()
                        .eq(BizWorkOrder::getParentId, workOrderId)
                        .orderByAsc(BizWorkOrder::getCreateTime))
                .stream()
                .map(child -> toView(child, order.getResultNote()))
                .toList();
        return new WorkOrderConsolePort.Family(self, parent, children);
    }

    public List<WorkOrderConsolePort.SimilarCase> similarResolved(Long workOrderId, int limit) {
        BizWorkOrder order = journal.require(workOrderId);
        int size = Math.min(Math.max(limit, 1), 5);
        return orders.selectList(Wrappers.<BizWorkOrder>lambdaQuery()
                        .eq(BizWorkOrder::getType, order.getType())
                        .eq(BizWorkOrder::getSlotId, order.getSlotId())
                        .eq(BizWorkOrder::getStatus, WorkOrderStatus.RESOLVED)
                        .ne(BizWorkOrder::getId, workOrderId)
                        .orderByDesc(BizWorkOrder::getClosedAt)
                        .last("limit " + size))
                .stream()
                .map(o -> new WorkOrderConsolePort.SimilarCase(o.getTitle(),
                        o.getResultNote() == null ? "" : o.getResultNote(),
                        o.getClosedAt() == null ? null : o.getClosedAt().toLocalDate().toString()))
                .toList();
    }

    public List<WorkOrderConsolePort.EventView> timeline(Long workOrderId) {
        return events.selectList(Wrappers.<BizWorkOrderEvent>lambdaQuery()
                .eq(BizWorkOrderEvent::getWorkOrderId, workOrderId)
                .orderByAsc(BizWorkOrderEvent::getCreateTime))
                .stream()
                .map(e -> new WorkOrderConsolePort.EventView(e.getEventType(), e.getActorType(), e.getOperatorId(),
                        e.getTraceId(), e.getDetail(), e.getCreateTime()))
                .toList();
    }

    public List<WorkOrderConsolePort.EvidenceView> evidenceFor(Long workOrderId) {
        BizWorkOrder order = journal.require(workOrderId);
        if (order.getSlotId() == null) {
            return List.of();
        }
        return evidencePort.byCompartment(order.getSlotId(), 60).stream()
                .map(e -> new WorkOrderConsolePort.EvidenceView(e.moment(), e.source(), e.presence(),
                        e.confidence(), e.aiLabel(), e.photoRef(), e.note(), e.at()))
                .toList();
    }

    public List<WorkOrderConsolePort.CustomerView> myOrders(Long customerId, int limit) {
        int size = Math.min(Math.max(limit, 1), 50);
        return orders.selectList(Wrappers.<BizWorkOrder>lambdaQuery()
                        .eq(BizWorkOrder::getCustomerId, customerId)
                        .eq(BizWorkOrder::getDeleted, 0)
                        .orderByDesc(BizWorkOrder::getCreateTime)
                        .last("limit " + size))
                .stream().map(this::toCustomerView).toList();
    }

    public WorkOrderConsolePort.CustomerView myOrder(Long workOrderId, Long customerId) {
        BizWorkOrder order = journal.require(workOrderId);
        if (customerId == null || !customerId.equals(order.getCustomerId())) {
            throw new BizException(ResultCode.FORBIDDEN, "这张工单不属于当前账号");
        }
        return toCustomerView(order, csatOf(workOrderId));
    }

    private WorkOrderConsolePort.WorkOrderView toView(BizWorkOrder o) {
        return toView(o, null);
    }

    private BizWorkOrderCsat csatOf(Long workOrderId) {
        return csats.selectOne(Wrappers.<BizWorkOrderCsat>lambdaQuery()
                .eq(BizWorkOrderCsat::getWorkOrderId, workOrderId));
    }

    private WorkOrderConsolePort.CustomerView toCustomerView(BizWorkOrder o) {
        return toCustomerView(o, null);
    }

    private WorkOrderConsolePort.CustomerView toCustomerView(BizWorkOrder o, BizWorkOrderCsat csat) {
        return new WorkOrderConsolePort.CustomerView(o.getId(), o.getOrderNo(), o.getTitle(),
                o.getStatus().name(),
                o.getCategory() == null ? null : o.getCategory().name(),
                o.getPriority() == null ? null : o.getPriority().name(),
                o.getCloseReason() == null ? null : o.getCloseReason().name(),
                o.getResultNote(), o.getSlotNo(), journal.readSnapshot(o, "cabinetNo"),
                journal.readSnapshot(o, "siteAddress"), o.getResponseDueAt(), o.getResolveDueAt(),
                o.getClosedAt(), o.getCreateTime(), o.getMergedIntoId(),
                o.getReopenedCount() == null ? 0 : o.getReopenedCount(),
                csat == null ? null : csat.getScore());
    }

    private WorkOrderConsolePort.WorkOrderView toView(BizWorkOrder o, String parentResultNote) {
        return toView(o, parentResultNote, null);
    }

    private WorkOrderConsolePort.WorkOrderView toView(BizWorkOrder o, String parentResultNote, BizWorkOrderCsat csat) {
        if (PhoneVisibility.allowed() && o.getCustomerId() != null) {
            phoneAuditor.record(o.getCustomerId());
        }
        return new WorkOrderConsolePort.WorkOrderView(o.getId(),
                o.getKind() == null ? null : o.getKind().name(),
                o.getSource() == null ? null : o.getSource().name(),
                o.getType() == null ? null : o.getType().name(),
                o.getIntent() == null ? null : o.getIntent().name(),
                o.getCategory() == null ? null : o.getCategory().name(),
                o.getPriority() == null ? null : o.getPriority().name(),
                o.getImpact() == null ? null : o.getImpact().name(),
                o.getUrgency() == null ? null : o.getUrgency().name(),
                Boolean.TRUE.equals(o.getUrgent()),
                o.getStatus().name(), o.getQueueSide(), o.getAssigneeGroup(), o.getSlotNo(),
                journal.readSnapshot(o, "cabinetNo"), journal.readSnapshot(o, "siteAddress"), o.getOrderNo(),
                PhoneVisibility.visible(journal.readSnapshot(o, "customerPhone")), o.getTitle(),
                journal.readSnapshot(o, "detection"), o.getResultNote(),
                o.getCloseReason() == null ? null : o.getCloseReason().name(),
                o.getAssigneeId(), o.getParentId(), parentResultNote,
                o.getDueAt(), o.getResponseDueAt(), o.getResolveDueAt(), o.getFirstResponseAt(),
                o.getClosedAt(), o.getCreateTime(), o.getHandoffJson(),
                o.getReopenedCount() == null ? 0 : o.getReopenedCount(),
                o.getSlaPauseReason(),
                csat == null ? null : csat.getScore(),
                csat == null ? null : csat.getComment());
    }
}
