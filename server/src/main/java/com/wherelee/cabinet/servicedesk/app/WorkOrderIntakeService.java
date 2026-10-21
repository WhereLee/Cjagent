package com.wherelee.cabinet.servicedesk.app;

import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.enums.TaskType;
import com.wherelee.cabinet.locker.api.OnlineState;
import com.wherelee.cabinet.locker.api.SlotQueryPort;
import com.wherelee.cabinet.servicedesk.api.ActorType;
import com.wherelee.cabinet.servicedesk.api.WorkOrderImpact;
import com.wherelee.cabinet.servicedesk.api.WorkOrderKind;
import com.wherelee.cabinet.servicedesk.api.WorkOrderPriority;
import com.wherelee.cabinet.servicedesk.api.WorkOrderSource;
import com.wherelee.cabinet.servicedesk.api.WorkOrderStatus;
import com.wherelee.cabinet.servicedesk.domain.BizWorkOrder;
import com.wherelee.cabinet.servicedesk.infra.BizWorkOrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 开单与分诊：工单进入系统的唯一入口（异常上报、用户诉求、AI 交接都从这里进来）。
 *
 * <p>为什么拆出来：开单的难点是<b>幂等</b>——巡检每轮都会看到同一个没关的门，靠“先查有没有”再插
 * 在并发下会写出两张。这里的顺序是“直接插，撞唯一索引就回读赢的那张”，索引才是最终裁判。
 * 分诊规则的重算也属于同一件事（这张单该在哪个队列、什么优先级、两个时限多长）。
 */
@Service
public class WorkOrderIntakeService {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderIntakeService.class);

    private final BizWorkOrderMapper orders;
    private final WorkOrderJournal journal;
    private final WorkOrderSlaService sla;
    private final WorkOrderTriageService triage;
    /** 分诊事实之一：柜机在线快照（离线时门故障改按设备故障处置）。 */
    private final SlotQueryPort slotQueries;

    public WorkOrderIntakeService(BizWorkOrderMapper orders, WorkOrderJournal journal,
                                  WorkOrderSlaService sla, WorkOrderTriageService triage,
                                  SlotQueryPort slotQueries) {
        this.orders = orders;
        this.journal = journal;
        this.sla = sla;
        this.triage = triage;
        this.slotQueries = slotQueries;
    }

    /**
     * 开单（旧入参）：把所有字段映射成智能工单口径后走同一个入口。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Long open(WorkOrderService.NewOrder cmd) {
        return open(new WorkOrderService.NewOrderV2(
                cmd.tenantId(), WorkOrderKind.INCIDENT, cmd.type(), WorkOrderSource.SYSTEM,
                null, null, cmd.urgent(), priorityOf(cmd.urgent()), WorkOrderImpact.SINGLE,
                cmd.urgent() ? com.wherelee.cabinet.servicedesk.api.WorkOrderUrgency.HIGH
                        : com.wherelee.cabinet.servicedesk.api.WorkOrderUrgency.NORMAL,
                WorkOrderService.SIDE_ONLINE, WorkOrderService.SIDE_ONLINE,
                cmd.siteId(), cmd.cabinetId(), cmd.slotId(), cmd.slotNo(),
                cmd.orderNo(), cmd.storageId(), cmd.customerId(), cmd.customerPhone(),
                cmd.siteAddress(), cmd.cabinetNo(), cmd.title(), cmd.detection(),
                cmd.dueAt(), cmd.dueAt(), cmd.dueAt(), null, null));
    }

    /**
     * 智能工单统一开单入口：异常和用户请求都从这里进来，只是 kind/source 不同。
     *
     * <p>开单靠唯一索引兜并发，冲突后要回读“赢了的那张”。MySQL 默认 REPEATABLE READ 下，
     * 第一次查询建立的快照看不到冲突后刚提交的行，回读会误判“索引冲突却找不到”。
     * READ COMMITTED 让回读看到最新提交，业务结果不变。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Long open(WorkOrderService.NewOrderV2 cmd) {
        BizWorkOrder existing = journal.findOpen(cmd.kind(), cmd.type(), cmd.slotId());
        if (existing != null) {
            journal.note(existing, "REPEATED", null, "同一问题再次被观察到：" + cmd.detection(),
                    actorTypeOf(cmd.source()), cmd.traceId());
            return existing.getId();
        }

        BizWorkOrder order = new BizWorkOrder();
        order.setTenantId(cmd.tenantId());
        order.setKind(kindOf(cmd.kind()));
        order.setSource(sourceOf(cmd.source()));
        order.setType(cmd.type());
        order.setIntent(cmd.intent());
        order.setCategory(cmd.category());
        order.setUrgent(cmd.urgent());
        order.setPriority(cmd.priority() == null ? priorityOf(cmd.urgent()) : cmd.priority());
        order.setImpact(cmd.impact());
        order.setUrgency(cmd.urgency());
        order.setStatus(WorkOrderStatus.PENDING);
        order.setQueueSide(cmd.queueSide() == null ? WorkOrderService.SIDE_ONLINE : cmd.queueSide());
        order.setAssigneeGroup(cmd.assigneeGroup());
        order.setActiveFlag(1);
        order.setSiteId(cmd.siteId());
        order.setCabinetId(cmd.cabinetId());
        order.setSlotId(cmd.slotId());
        order.setSlotNo(cmd.slotNo());
        order.setOrderNo(cmd.orderNo());
        order.setStorageId(cmd.storageId());
        order.setCustomerId(cmd.customerId());
        order.setTitle(cmd.title());
        order.setDueAt(cmd.dueAt());
        order.setResponseDueAt(cmd.responseDueAt());
        order.setResolveDueAt(cmd.resolveDueAt());
        order.setReopenedCount(0);
        order.setHandoffJson(cmd.handoffJson());
        order.setSnapshotJson(snapshot(cmd));
        try {
            orders.insert(order);
        } catch (DuplicateKeyException e) {
            // 并发下另一个巡检/另一次上报先到：回读那张，不算失败也不重复建
            BizWorkOrder won = journal.findOpen(cmd.kind(), cmd.type(), cmd.slotId());
            if (won == null) {
                // 冲突却查不到：唯一索引挡住了新单而那张已被关/软删，属于不一致现场，必须响
                throw new BizException(ResultCode.SYSTEM_ERROR,
                        "工单唯一索引冲突但找不到未关闭的那张，slotId=" + cmd.slotId());
            }
            journal.note(won, "REPEATED", null, "并发开单合并到已有工单：" + cmd.title(),
                    actorTypeOf(cmd.source()), cmd.traceId());
            return won.getId();
        }
        journal.note(order, "CREATED", null, cmd.title() + "｜检测：" + nullSafe(cmd.detection()),
                actorTypeOf(cmd.source()), cmd.traceId());
        // 开单即登记两个 SLA 任务：到点没人响应/没结论必须自己冒出来，不靠人惦记。
        sla.schedule(order);
        log.warn("工单已开 id={} kind={} source={} type={} priority={} slotNo={} orderNo={}",
                order.getId(), order.getKind(), order.getSource(), order.getType(),
                order.getPriority(), cmd.slotNo(), cmd.orderNo());
        return order.getId();
    }

    /** 重新分诊：规则调整后对一张已有单重算分类/队列/优先级/SLA，并留 TRIAGED 痕。 */
    @Transactional
    public void triage(Long workOrderId) {
        BizWorkOrder order = journal.require(workOrderId);
        if (order.getIntent() == null) {
            throw new BizException(ResultCode.BIZ_ERROR, "这张工单没有用户现象，无法分诊");
        }
        WorkOrderTriageService.Result result = triage.triage(order.getIntent(),
                new WorkOrderTriageService.Facts(
                        isCabinetOffline(order),
                        order.getImpact() == WorkOrderImpact.CABINET,
                        order.getImpact() == WorkOrderImpact.ACCOUNT,
                        Boolean.TRUE.equals(order.getUrgent())));
        LocalDateTime now = LocalDateTime.now();
        orders.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.<BizWorkOrder>lambdaUpdate()
                .eq(BizWorkOrder::getId, workOrderId)
                .set(BizWorkOrder::getCategory, result.category())
                .set(BizWorkOrder::getPriority, result.priority())
                .set(BizWorkOrder::getImpact, result.impact())
                .set(BizWorkOrder::getUrgency, result.urgency())
                .set(BizWorkOrder::getQueueSide, result.queueSide())
                .set(BizWorkOrder::getAssigneeGroup, result.assigneeGroup())
                .set(BizWorkOrder::getResponseDueAt, now.plusMinutes(result.responseMinutes()))
                .set(BizWorkOrder::getResolveDueAt, now.plusMinutes(result.resolveMinutes())));
        journal.note(order, "TRIAGED", null,
                "命中规则 " + result.ruleCode() + " v" + result.ruleVersion()
                        + " → " + result.category() + "/" + result.priority(),
                ActorType.SYSTEM.name(), null);
    }

    private boolean isCabinetOffline(BizWorkOrder order) {
        if (order.getCabinetId() == null) {
            return false;
        }
        var cabinet = slotQueries.cabinet(order.getCabinetId()).orElse(null);
        return cabinet != null
                && OnlineState.OFFLINE.name().equals(cabinet.onlineState());
    }

    private WorkOrderKind kindOf(WorkOrderKind kind) {
        return kind == null ? WorkOrderKind.INCIDENT : kind;
    }

    private WorkOrderSource sourceOf(WorkOrderSource source) {
        return source == null ? WorkOrderSource.SYSTEM : source;
    }

    private WorkOrderPriority priorityOf(boolean urgent) {
        return urgent ? WorkOrderPriority.P1 : WorkOrderPriority.P3;
    }

    private String actorTypeOf(WorkOrderSource source) {
        return switch (sourceOf(source)) {
            case AI -> ActorType.AI.name();
            case USER -> ActorType.USER.name();
            case DEVICE, SYSTEM -> ActorType.SYSTEM.name();
        };
    }

    /** 快照里放业务员判断性质需要的东西：位置、编号、时间、用户电话、检测值。 */
    private String snapshot(WorkOrderService.NewOrderV2 cmd) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"siteAddress\":\"").append(escape(cmd.siteAddress())).append("\",");
        sb.append("\"cabinetNo\":\"").append(escape(cmd.cabinetNo())).append("\",");
        sb.append("\"slotNo\":\"").append(escape(cmd.slotNo())).append("\",");
        sb.append("\"orderNo\":\"").append(escape(cmd.orderNo())).append("\",");
        sb.append("\"customerPhone\":\"").append(escape(cmd.customerPhone())).append("\",");
        sb.append("\"detection\":\"").append(escape(cmd.detection())).append("\",");
        sb.append("\"openedAt\":\"").append(LocalDateTime.now()).append("\"");
        return sb.append("}").toString();
    }

    private String escape(String raw) {
        return raw == null ? "" : raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String nullSafe(String raw) {
        return raw == null ? "未测" : raw;
    }
}
