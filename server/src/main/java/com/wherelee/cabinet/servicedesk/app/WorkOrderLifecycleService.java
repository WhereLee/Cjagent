package com.wherelee.cabinet.servicedesk.app;

import com.wherelee.cabinet.notify.api.NoticeChannel;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.enums.CloseReason;
import com.wherelee.cabinet.locker.api.SlotQueryPort;
import com.wherelee.cabinet.locker.api.SlotSnapshot;
import com.wherelee.cabinet.notify.api.NoticePort;
import com.wherelee.cabinet.servicedesk.api.ActorType;
import com.wherelee.cabinet.servicedesk.api.WorkOrderKind;
import com.wherelee.cabinet.servicedesk.api.WorkOrderPriority;
import com.wherelee.cabinet.servicedesk.api.WorkOrderSource;
import com.wherelee.cabinet.servicedesk.api.WorkOrderStatus;
import com.wherelee.cabinet.servicedesk.api.WorkOrderType;
import com.wherelee.cabinet.servicedesk.domain.BizWorkOrder;
import com.wherelee.cabinet.servicedesk.infra.BizWorkOrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class WorkOrderLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderLifecycleService.class);

    private static final String SIDE_FIELD = WorkOrderService.SIDE_FIELD;

    private final BizWorkOrderMapper orders;
    private final NoticePort notices;
    private final SlotQueryPort slotQueries;
    private final WorkOrderHandoffService handoffs;
    private final WorkOrderRoutingService routing;
    private final WorkOrderJournal journal;
    private final WorkOrderSlaService sla;

    public WorkOrderLifecycleService(BizWorkOrderMapper orders, NoticePort notices,
                                     SlotQueryPort slotQueries, WorkOrderHandoffService handoffs,
                                     WorkOrderRoutingService routing, WorkOrderJournal journal,
                                     WorkOrderSlaService sla) {
        this.orders = orders;
        this.notices = notices;
        this.slotQueries = slotQueries;
        this.handoffs = handoffs;
        this.routing = routing;
        this.journal = journal;
        this.sla = sla;
    }

    @Transactional
    public void claim(Long workOrderId, Long operatorId) {
        if (orders.claim(workOrderId, operatorId) == 0) {
            BizWorkOrder now = journal.require(workOrderId);
            if (now.getStatus() == WorkOrderStatus.CLAIMED && operatorId.equals(now.getAssigneeId())) {
                return;
            }
            throw new BizException(ResultCode.BIZ_ERROR,
                    "这张工单已被他人认领或已关闭，当前状态：" + now.getStatus());
        }
        BizWorkOrder claimed = journal.require(workOrderId);
        orders.update(null, Wrappers.<BizWorkOrder>lambdaUpdate()
                .eq(BizWorkOrder::getId, workOrderId)
                .isNull(BizWorkOrder::getFirstResponseAt)
                .set(BizWorkOrder::getFirstResponseAt, LocalDateTime.now()));
        sla.cancelResponse(claimed);
        journal.note(claimed, "CLAIMED", operatorId, "已认领");
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Long resolve(Long workOrderId, Long operatorId, String resultNote, boolean needOffline) {
        return resolve(workOrderId, operatorId, resultNote, needOffline, CloseReason.MANUAL);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Long resolve(Long workOrderId, Long operatorId, String resultNote, boolean needOffline,
                        CloseReason requestedCloseReason) {
        if (resultNote == null || resultNote.isBlank()) {
            throw new BizException(ResultCode.BIZ_ERROR, "处理结论必填：工单的价值就在“谁说了什么”");
        }
        BizWorkOrder order = journal.require(workOrderId);
        CloseReason closeReason = allowedCloseReason(order, requestedCloseReason);
        if (orders.resolve(workOrderId, resultNote, closeReason.name()) == 0) {
            throw new BizException(ResultCode.BIZ_ERROR, "这张工单已关闭，不能重复处理");
        }
        sla.cancel(order);
        journal.note(order, "RESOLVED", operatorId, resultNote + "（关闭依据：" + closeReason + "）");
        if (!needOffline) {
            return null;
        }
        BizWorkOrder existingChild = journal.findOpenInQueue(order.getKind(), order.getType(), order.getSlotId(), SIDE_FIELD);
        if (existingChild != null) {
            journal.note(existingChild, "DISPATCHED", operatorId, "复用已有线下工单，父单 id=" + order.getId());
            return existingChild.getId();
        }
        BizWorkOrder child = new BizWorkOrder();
        child.setTenantId(order.getTenantId());
        child.setKind(order.getKind());
        child.setSource(WorkOrderSource.SYSTEM);
        child.setType(order.getType());
        child.setIntent(order.getIntent());
        child.setCategory(order.getCategory());
        child.setUrgent(order.getUrgent());
        child.setPriority(order.getPriority());
        child.setImpact(order.getImpact());
        child.setUrgency(order.getUrgency());
        child.setStatus(WorkOrderStatus.PENDING);
        child.setQueueSide(SIDE_FIELD);
        child.setAssigneeGroup("FIELD_OPS");
        child.setAssigneeId(routing.dutyUser("FIELD_OPS"));
        child.setActiveFlag(1);
        child.setParentId(order.getId());
        child.setSiteId(order.getSiteId());
        child.setCabinetId(order.getCabinetId());
        child.setSlotId(order.getSlotId());
        child.setSlotNo(order.getSlotNo());
        child.setOrderNo(order.getOrderNo());
        child.setStorageId(order.getStorageId());
        child.setCustomerId(order.getCustomerId());
        child.setTitle("线下到场：" + order.getTitle());
        child.setSnapshotJson(order.getSnapshotJson());
        child.setHandoffJson(handoffs.forChild(order.getHandoffJson(), resultNote));
        child.setResponseDueAt(order.getResponseDueAt());
        child.setResolveDueAt(order.getResolveDueAt());
        child.setReopenedCount(0);
        child.setDueAt(LocalDateTime.now().plusHours(order.getUrgent() != null && order.getUrgent() ? 2 : 24));
        try {
            orders.insert(child);
        } catch (DuplicateKeyException raced) {
            BizWorkOrder won = journal.findOpenInQueue(order.getKind(), order.getType(), order.getSlotId(), SIDE_FIELD);
            if (won == null) {
                throw new BizException(ResultCode.SYSTEM_ERROR,
                        "线下工单唯一索引冲突但找不到未关闭的那张，slotId=" + order.getSlotId());
            }
            return won.getId();
        }
        journal.note(child, "DISPATCHED", operatorId, "线上判断需线下运维，父单 id=" + order.getId());
        sla.schedule(child);
        log.warn("已派发线下工单 parent={} child={} urgent={}", order.getId(), child.getId(), child.getUrgent());
        return child.getId();
    }

    @Transactional
    public int autoClose(Long slotId, WorkOrderType type) {
        int closed = orders.autoCloseBySlot(slotId, type.name());
        if (closed > 0) {
            List<BizWorkOrder> list = orders.selectList(Wrappers.<BizWorkOrder>lambdaQuery()
                    .eq(BizWorkOrder::getKind, WorkOrderKind.INCIDENT)
                    .eq(BizWorkOrder::getSlotId, slotId)
                    .eq(BizWorkOrder::getType, type)
                    .eq(BizWorkOrder::getStatus, WorkOrderStatus.AUTO_CLOSED)
                    .orderByDesc(BizWorkOrder::getClosedAt)
                    .last("limit " + closed));
            for (BizWorkOrder o : list) {
                sla.cancel(o);
                journal.note(o, "AUTO_CLOSED", null, "异常事实已消失，工单自动转化留底（非人工结论）");
                String where = (o.getSlotNo() == null ? "格口" : "格口 " + o.getSlotNo())
                        + "（" + o.getTitle() + "）";
                notices.emit(o.getTenantId(),
                        NoticeChannel.SYSTEM_MESSAGE,
                        "WORK_ORDER_AUTO_CLOSED", "WO" + o.getId() + ":AUTO_CLOSE:MSG",
                        "你的工单" + where + " 已因异常解除自动关闭。如果问题仍存在，"
                                + "可在工单详情里点“还没解决”。",
                        o.getCustomerId(), o.getId(), o.getOrderNo());
                notices.emit(o.getTenantId(),
                        NoticeChannel.QUEUE,
                        "WORK_ORDER_AUTO_CLOSED", "WO" + o.getId() + ":AUTO_CLOSE:QUEUE",
                        "工单 WO" + o.getId() + " 因事实恢复自动关闭：" + where,
                        null, o.getId(), o.getOrderNo());
            }
            log.info("工单自动转化留底 slotId={} type={} 条数={}", slotId, type, closed);
        }
        return closed;
    }

    private CloseReason allowedCloseReason(BizWorkOrder order, CloseReason requested) {
        if (requested == null) {
            return CloseReason.MANUAL;
        }
        return switch (requested) {
            case MANUAL -> CloseReason.MANUAL;
            case FACT_RECOVERED -> {
                if (!factRecovered(order)) {
                    throw new BizException(ResultCode.BIZ_ERROR,
                            "格口异常尚未解除，不能按“事实已恢复”关闭；请按人工结论关闭");
                }
                yield CloseReason.FACT_RECOVERED;
            }
            case USER_CONFIRMED -> throw new BizException(ResultCode.BIZ_ERROR,
                    "“用户确认已解决”只能由用户在小程序确认，业务员不能代写");
            case MERGED, CANCELLED -> throw new BizException(ResultCode.BIZ_ERROR,
                    "合并/作废不是处理反馈的关闭依据");
        };
    }

    private boolean factRecovered(BizWorkOrder order) {
        if (order.getSlotId() == null) {
            return false;
        }
        SlotSnapshot slot = slotQueries.slot(order.getSlotId()).orElse(null);
        return slot != null && slot.anomaly() == null;
    }

    @Transactional
    public void confirmResolved(Long workOrderId, Long customerId, String note) {
        BizWorkOrder order = journal.require(workOrderId);
        if (customerId == null || !customerId.equals(order.getCustomerId())) {
            throw new BizException(ResultCode.FORBIDDEN, "这张工单不属于当前账号");
        }
        String detail = note == null || note.isBlank() ? "" : "；用户备注：" + journal.truncate(note.trim());
        if (orders.confirmResolved(workOrderId, customerId, detail) == 0) {
            throw new BizException(ResultCode.BIZ_ERROR, "这张工单已关闭，或不属于当前账号");
        }
        sla.cancel(order);
        journal.note(order, "USER_CONFIRMED", customerId, "用户确认问题已解决" + detail,
                ActorType.USER.name(), null);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Long reopen(Long workOrderId, Long customerId, String reason) {
        BizWorkOrder order = journal.require(workOrderId);
        if (customerId == null || !customerId.equals(order.getCustomerId())) {
            throw new BizException(ResultCode.FORBIDDEN, "这张工单不属于当前账号");
        }
        String detail = reason == null || reason.isBlank() ? "用户反馈问题还没解决" : journal.truncate(reason.trim());
        try {
            if (orders.reopen(workOrderId, customerId, "；用户反馈：" + detail) == 0) {
                throw new BizException(ResultCode.BIZ_ERROR, "只能重开本人已关闭的工单");
            }
        } catch (DuplicateKeyException conflict) {
            BizWorkOrder target = journal.findOpenInQueue(order.getKind(), order.getType(),
                    order.getSlotId(), order.getQueueSide());
            if (target == null) {
                throw new BizException(ResultCode.SYSTEM_ERROR,
                        "重开撞上未关闭工单唯一索引，但找不到那张单，id=" + workOrderId);
            }
            orders.markMerged(workOrderId, target.getId(),
                    "；重开时已有同类未关闭工单，合并到 #" + target.getId());
            journal.note(target, "REOPENED", customerId,
                    "用户反馈问题还没解决，原单 #" + workOrderId + " 已并入本单",
                    ActorType.USER.name(), null);
            return target.getId();
        }
        BizWorkOrder reopened = journal.require(workOrderId);
        journal.note(reopened, "REOPENED", customerId, detail, ActorType.USER.name(), null);
        sla.schedule(reopened);
        log.warn("用户重开工单 id={} customer={} reopenedCount={}",
                workOrderId, customerId, reopened.getReopenedCount());
        return workOrderId;
    }

    @Transactional
    public void notePhoneCall(Long workOrderId, Long operatorId, String resultNote) {
        if (resultNote == null || resultNote.isBlank()) {
            throw new BizException(ResultCode.BIZ_ERROR, "联系结果必填：联系上了/未接/约定多久处理");
        }
        BizWorkOrder order = journal.require(workOrderId);
        journal.note(order, "PHONE_CALLED", operatorId, journal.truncate(resultNote));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Long openFieldTwin(Long parentWorkOrderId, String reason) {
        BizWorkOrder parent = journal.require(parentWorkOrderId);
        if (SIDE_FIELD.equals(parent.getQueueSide())) {
            return parent.getId();
        }
        BizWorkOrder existing = journal.findOpenInQueue(parent.getKind(), parent.getType(), parent.getSlotId(), SIDE_FIELD);
        if (existing != null) {
            return existing.getId();
        }
        BizWorkOrder child = new BizWorkOrder();
        child.setTenantId(parent.getTenantId());
        child.setKind(parent.getKind());
        child.setSource(WorkOrderSource.SYSTEM);
        child.setType(parent.getType());
        child.setIntent(parent.getIntent());
        child.setCategory(parent.getCategory());
        child.setUrgent(true);
        child.setPriority(WorkOrderPriority.P1);
        child.setImpact(parent.getImpact());
        child.setUrgency(parent.getUrgency());
        child.setStatus(WorkOrderStatus.PENDING);
        child.setQueueSide(SIDE_FIELD);
        child.setAssigneeGroup("FIELD_OPS");
        child.setAssigneeId(routing.dutyUser("FIELD_OPS"));
        child.setActiveFlag(1);
        child.setParentId(parent.getId());
        child.setSiteId(parent.getSiteId());
        child.setCabinetId(parent.getCabinetId());
        child.setSlotId(parent.getSlotId());
        child.setSlotNo(parent.getSlotNo());
        child.setOrderNo(parent.getOrderNo());
        child.setStorageId(parent.getStorageId());
        child.setCustomerId(parent.getCustomerId());
        child.setTitle("线下到场：" + parent.getTitle());
        child.setSnapshotJson(parent.getSnapshotJson());
        child.setHandoffJson(handoffs.forChild(parent.getHandoffJson(), parent.getResultNote()));
        child.setResponseDueAt(parent.getResponseDueAt());
        child.setResolveDueAt(parent.getResolveDueAt());
        child.setReopenedCount(0);
        child.setDueAt(java.time.LocalDateTime.now().plusHours(24));
        try {
            orders.insert(child);
        } catch (DuplicateKeyException raced) {
            BizWorkOrder won = journal.findOpenInQueue(parent.getKind(), parent.getType(), parent.getSlotId(), SIDE_FIELD);
            return won == null ? child.getId() : won.getId();
        }
        journal.note(child, "DISPATCHED", null, reason + "（系统按时间阈值自动派发）");
        sla.schedule(child);
        log.warn("自动派发线下工单 parent={} child={} {}", parent.getId(), child.getId(), reason);
        return child.getId();
    }
}
