package com.wherelee.cabinet.servicedesk.app;
import com.wherelee.cabinet.locker.api.OnlineState;
import com.wherelee.cabinet.locker.api.SlotQueryPort;
import com.wherelee.cabinet.locker.api.SlotSnapshot;

import com.wherelee.cabinet.assistant.api.WorkOrderBriefPort;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wherelee.cabinet.platform.web.PageSupport;
import com.wherelee.cabinet.common.api.PageQuery;
import com.wherelee.cabinet.common.api.PageResult;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.servicedesk.domain.BizWorkOrder;
import com.wherelee.cabinet.servicedesk.domain.BizWorkOrderEvent;
import com.wherelee.cabinet.servicedesk.api.ActorType;
import com.wherelee.cabinet.domain.enums.CloseReason;
import com.wherelee.cabinet.servicedesk.api.WorkOrderCategory;
import com.wherelee.cabinet.servicedesk.api.WorkOrderImpact;
import com.wherelee.cabinet.servicedesk.api.WorkOrderIntent;
import com.wherelee.cabinet.servicedesk.api.WorkOrderKind;
import com.wherelee.cabinet.servicedesk.api.WorkOrderPriority;
import com.wherelee.cabinet.servicedesk.api.WorkOrderSource;
import com.wherelee.cabinet.servicedesk.api.WorkOrderStatus;
import com.wherelee.cabinet.servicedesk.api.WorkOrderType;
import com.wherelee.cabinet.servicedesk.api.WorkOrderUrgency;
import com.wherelee.cabinet.domain.enums.TaskType;
import com.wherelee.cabinet.servicedesk.infra.BizWorkOrderEventMapper;
import com.wherelee.cabinet.servicedesk.infra.BizWorkOrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 工单流转（19B）。
 *
 * <p>三件事决定了它的写法，都不是风格偏好：
 * <ol>
 *   <li><b>开单幂等靠唯一索引</b>：巡检每轮都会看到同一个没关的门，如果靠“先查有没有”再插，
 *       并发下会写出两张。这里直接插，撞了 {@code uk_work_order_open} 就回读那张 ——
 *       索引才是最终裁判（与格口防超卖同一个手法）。</li>
 *   <li><b>“人处理的”与“事实自己消失的”必须分状态</b>：RESOLVED 是结论，AUTO_CLOSED 是留底。
 *       混写就等于把机器的动作算成人的功劳。</li>
 *   <li><b>派线下是“另开一张”，不是改状态</b>：线上为什么派人、线下到场做了什么，是两段独立历史，
 *       合成一条就互相覆盖。</li>
 * </ol>
 */
@Service
public class WorkOrderService {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderService.class);

    /** 队列侧常量：两条队列对应两个角色，不靠角色名硬猜。 */
    public static final String SIDE_ONLINE = "ONLINE";
    public static final String SIDE_FIELD = "FIELD";

    private final BizWorkOrderMapper orders;
    private final BizWorkOrderEventMapper events;
    /** 19D：升级时同时生成“必须打电话”待办与给客户的站内消息。 */
    private final com.wherelee.cabinet.notify.api.NoticePort notices;
    /** 19C：证据记录（四个采集点）随工单一起看，不然采集了也等于没人看到。 */
    private final com.wherelee.cabinet.device.api.EvidencePort evidencePort;
    /** 分诊事实之一：柜机在线快照（离线时门故障改按设备故障处置）。 */
    /** 柜机在线与格口异常都从这里看（不再直接读 locker 的表）。 */
    private final SlotQueryPort slotQueries;
    private final WorkOrderTriageService triage;
    /**
     * 延迟任务用 ObjectProvider 取：TaskHandler 反过来依赖本服务（超时转工单），
     * 直接注入会形成构造环（DelayTaskService → handlers → WorkOrderService）。
     */
    private final ObjectProvider<com.wherelee.cabinet.application.task.DelayTaskService> schedulerProvider;
    /** 交接事实：父单结论与 AI 尝试要能传到子单。 */
    private final WorkOrderHandoffService handoffs;
    /** 责任组 → 值班人（组内轮转，不做排班表）。 */
    private final WorkOrderRoutingService routing;
    /** 取单与事件留痕：生命周期、SLA、查询三处共用同一条流水。 */
    private final WorkOrderJournal journal;
    /** 两个 SLA 时限与门未关阶梯。 */
    private final WorkOrderSlaService sla;

    public WorkOrderService(BizWorkOrderMapper orders, BizWorkOrderEventMapper events,
                            com.wherelee.cabinet.notify.api.NoticePort notices,
                            com.wherelee.cabinet.device.api.EvidencePort evidencePort,
                            SlotQueryPort slotQueries,
                            WorkOrderTriageService triage,
                            ObjectProvider<com.wherelee.cabinet.application.task.DelayTaskService> schedulerProvider,
                            WorkOrderHandoffService handoffs, WorkOrderRoutingService routing,
                            WorkOrderJournal journal, WorkOrderSlaService sla) {
        this.orders = orders;
        this.events = events;
        this.notices = notices;
        this.evidencePort = evidencePort;
        this.slotQueries = slotQueries;
        this.triage = triage;
        this.schedulerProvider = schedulerProvider;
        this.handoffs = handoffs;
        this.routing = routing;
        this.journal = journal;
        this.sla = sla;
    }

    /** 暂停 SLA：只停两个时限的前进，不改工单状态（时间算术在 SLA 服务里）。 */
    public void pauseSla(Long workOrderId, Long operatorId) {
        sla.pause(workOrderId, operatorId);
    }

    /** 恢复 SLA：把暂停时长补回响应/解决时限。 */
    public void resumeSla(Long workOrderId, Long operatorId) {
        sla.resume(workOrderId, operatorId);
    }

    /** 记一次“被催办”（门未关阶梯用）。 */
    public boolean remind(Long slotId, WorkOrderType type, String detail) {
        return sla.remind(slotId, type, detail);
    }

    /** 阶梯到点：升级为紧急并产出电话待办与站内消息。 */
    public boolean escalate(Long slotId, WorkOrderType type, String detail) {
        return sla.escalate(slotId, type, detail);
    }

    /** 这张单已催过几次（阶梯判重用）。 */
    public int remindedTimes(Long slotId, WorkOrderType type) {
        return sla.remindedTimes(slotId, type);
    }

    /** 一条证据的可读视图（采集点 + 三值结论 + 置信度 + AI 标签 + 照片引用）。 */
    public record EvidenceView(String moment, String source, String presence, java.math.BigDecimal confidence,
                               String aiLabel, String photoRef, String note, java.time.LocalDateTime at) {
    }

    /**
     * 这张工单相关格口的证据时间线（19C）。
     *
     * <p>按格口取而不是按单号取：代关、上报遗留这些事可能根本没有活动单可挂，
     * 而业务员要看的是这个格子先后被看到过什么。
     */
    public java.util.List<EvidenceView> evidenceFor(Long workOrderId) {
        BizWorkOrder order = journal.require(workOrderId);
        if (order.getSlotId() == null) {
            return java.util.List.of();
        }
        return evidencePort.byCompartment(order.getSlotId(), 60).stream()
                .map(e -> new EvidenceView(e.moment(), e.source(), e.presence(),
                        e.confidence(), e.aiLabel(), e.photoRef(), e.note(), e.at()))
                .toList();
    }

    /**
     * 开单入参。<b>所有字段都是“当时看到的事实”</b>，不是引用：电话与格口编号必须快照，
     * 否则用户改绑手机之后，这张单就再也说不清当时联系的是谁。
     */
    public record NewOrder(Long tenantId, WorkOrderType type, boolean urgent,
                           Long siteId, Long cabinetId, Long slotId, String slotNo,
                           String orderNo, Long storageId, Long customerId, String customerPhone,
                           String siteAddress, String cabinetNo, String title, String detection,
                           LocalDateTime dueAt) {
    }

    /** 智能工单入参：在旧 NewOrder 上增加 kind/source/intent/category/priority/SLA/交接。 */
    public record NewOrderV2(Long tenantId, WorkOrderKind kind, WorkOrderType type, WorkOrderSource source,
                             WorkOrderIntent intent, WorkOrderCategory category, boolean urgent,
                             WorkOrderPriority priority, WorkOrderImpact impact, WorkOrderUrgency urgency,
                             String assigneeGroup, String queueSide,
                             Long siteId, Long cabinetId, Long slotId, String slotNo,
                             String orderNo, Long storageId, Long customerId, String customerPhone,
                             String siteAddress, String cabinetNo, String title, String detection,
                             LocalDateTime dueAt, LocalDateTime responseDueAt, LocalDateTime resolveDueAt,
                             String handoffJson, String traceId) {
    }

    /** 队列视图：接口层不回传实体（ArchUnit 会拦，而且实体带着一堆流转细节）。 */
    public record View(Long id, String kind, String source, String type, String intent, String category,
                       String priority, String impact, String urgency, boolean urgent, String status,
                       String queueSide, String assigneeGroup, String slotNo, String cabinetNo,
                       String siteAddress, String orderNo, String customerPhone, String title,
                       String detection, String resultNote, String closeReason, Long assigneeId,
                       Long parentId, String parentResultNote, LocalDateTime dueAt,
                       LocalDateTime responseDueAt, LocalDateTime resolveDueAt,
                       LocalDateTime firstResponseAt, LocalDateTime closedAt, LocalDateTime createTime,
                       String handoffJson, int reopenedCount) {
    }

    /** 留痕视图。 */
    public record EventView(String eventType, String actorType, Long operatorId, String traceId,
                            String detail, LocalDateTime at) {
    }

    /**
     * 用户侧工单视图：只给用户自己关心的字段。
     * 交接 JSON、快照电话、内部指派这些是给业务员看的，不能顺手塞进小程序。
     */
    public record CustomerView(Long id, String orderNo, String title, String status, String category,
                               String priority, String closeReason, String resultNote, String slotNo,
                               String cabinetNo, String siteAddress, LocalDateTime responseDueAt,
                               LocalDateTime resolveDueAt, LocalDateTime closedAt, LocalDateTime createTime,
                               Long mergedIntoId, int reopenedCount) {
    }

    /**
     * 开单（幂等）。已存在同格口同类型的未关闭工单时，返回那张的 ID 而不新建，
     * 并把它“又被观察到一次”记进流水——这样队列长度始终是“有几件事要办”。
     */
    // 开单靠唯一索引兜并发，冲突后要回读“赢了的那张”。MySQL 默认 REPEATABLE READ 下，
    // 第一次查询建立的快照看不到冲突后刚提交的行，回读会误判“索引冲突却找不到”。
    // READ COMMITTED 让回读看到最新提交，业务结果不变。
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Long open(NewOrder cmd) {
        return open(new NewOrderV2(
                cmd.tenantId(), WorkOrderKind.INCIDENT, cmd.type(), WorkOrderSource.SYSTEM,
                null, null, cmd.urgent(), priorityOf(cmd.urgent()), WorkOrderImpact.SINGLE,
                cmd.urgent() ? WorkOrderUrgency.HIGH : WorkOrderUrgency.NORMAL,
                SIDE_ONLINE, SIDE_ONLINE,
                cmd.siteId(), cmd.cabinetId(), cmd.slotId(), cmd.slotNo(),
                cmd.orderNo(), cmd.storageId(), cmd.customerId(), cmd.customerPhone(),
                cmd.siteAddress(), cmd.cabinetNo(), cmd.title(), cmd.detection(),
                cmd.dueAt(), cmd.dueAt(), cmd.dueAt(), null, null));
    }

    /** 智能工单统一开单入口：异常和用户请求都从这里进来，只是 kind/source 不同。 */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Long open(NewOrderV2 cmd) {
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
        order.setQueueSide(cmd.queueSide() == null ? SIDE_ONLINE : cmd.queueSide());
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
        orders.update(null, Wrappers.<BizWorkOrder>lambdaUpdate()
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

    /** 认领：抢不到就是别人已经接了，给业务异常而不静默成功。 */
    @Transactional
    public void claim(Long workOrderId, Long operatorId) {
        if (orders.claim(workOrderId, operatorId) == 0) {
            BizWorkOrder now = journal.require(workOrderId);
            if (now.getStatus() == WorkOrderStatus.CLAIMED && operatorId.equals(now.getAssigneeId())) {
                return; // 重放友好：自己已经认领过，不报错也不重复写
            }
            throw new BizException(ResultCode.BIZ_ERROR,
                    "这张工单已被他人认领或已关闭，当前状态：" + now.getStatus());
        }
        BizWorkOrder claimed = journal.require(workOrderId);
        // 认领 = 响应：记录首次响应时刻，响应 SLA 任务同时取消（它已经完成使命）。
        orders.update(null, Wrappers.<BizWorkOrder>lambdaUpdate()
                .eq(BizWorkOrder::getId, workOrderId)
                .isNull(BizWorkOrder::getFirstResponseAt)
                .set(BizWorkOrder::getFirstResponseAt, LocalDateTime.now()));
        sla.cancelResponse(claimed);
        journal.note(claimed, "CLAIMED", operatorId, "已认领");
    }

    /**
     * 处理并反馈。{@code dispatchField=true} 时<b>另开一张线下工单</b>并把父单落 RESOLVED——
     * 顺序不能反：先派子单再关父单，中间崩了只会多一张待办，反过来会丢派单。
     *
     * @param needOffline 线上业务员的判断：“需不需要跑一趟”。用户口径里这是唯一派线下工单的依据
     */
    // 与自动派单并发时，子单冲突后的回读同样需要看到对方刚提交的行。
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Long resolve(Long workOrderId, Long operatorId, String resultNote, boolean needOffline) {
        return resolve(workOrderId, operatorId, resultNote, needOffline, CloseReason.MANUAL);
    }

    /**
     * 处理反馈 + 关闭依据。现场类工单不能由业务员代写“用户确认”；
     * 按事实恢复关闭时必须现场事实真的已恢复（异常已解除），否则只能写人工结论。
     */
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
        // 同一格口同类型在 FIELD 队列只能有一张未关闭单（唯一索引）。
        // 超 72 小时系统可能已经自动派过一张线下单，此时再点“需线下”必须复用它，
        // 否则会撞 uk_work_order_open 把父单的处理结论一起回滚。
        BizWorkOrder existingChild = journal.findOpenInQueue(order.getKind(), order.getType(), order.getSlotId(), SIDE_FIELD);
        if (existingChild != null) {
            journal.note(existingChild, "DISPATCHED", operatorId, "复用已有线下工单，父单 id=" + order.getId());
            return existingChild.getId();
        }
        BizWorkOrder child = new BizWorkOrder();
        child.setTenantId(order.getTenantId());
        child.setKind(kindOf(order.getKind()));
        child.setSource(WorkOrderSource.SYSTEM);
        child.setType(order.getType());
        child.setIntent(order.getIntent());
        child.setCategory(order.getCategory());
        // 线下工单继承级别：紧急异常意味着“需要快速处理”（用户口径）
        child.setUrgent(order.getUrgent());
        child.setPriority(order.getPriority());
        child.setImpact(order.getImpact());
        child.setUrgency(order.getUrgency());
        child.setStatus(WorkOrderStatus.PENDING);
        child.setQueueSide(SIDE_FIELD);
        child.setAssigneeGroup("FIELD_OPS");
        // 派线下时按责任组找值班人：组内轮转，不另建排班表；没配人时保持未指派
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
        // 复制交接事实 + 父单结论：线下到场第一眼就要知道“为什么派我出来、线上试过什么”
        child.setHandoffJson(handoffs.forChild(order.getHandoffJson(), resultNote));
        child.setResponseDueAt(order.getResponseDueAt());
        child.setResolveDueAt(order.getResolveDueAt());
        child.setReopenedCount(0);
        child.setDueAt(LocalDateTime.now().plusHours(order.getUrgent() != null && order.getUrgent() ? 2 : 24));
        try {
            orders.insert(child);
        } catch (DuplicateKeyException raced) {
            // 与自动派单并发：别人先插成功，回读那张即可，父单已经 RESOLVED，不能因子单冲突回滚
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

    /**
     * 事实自行消失时的自动转化（门被关上、遗留被取走）。<b>不删除、不改写成人做的结论</b>，
     * 也不管它有没有被认领——没认领的更要留底，那正是“系统发现→自己好了”的样本。
     */
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
                // 自动关闭不是“有人处理了”，但用户和队列都必须知道这件事已经结束：
                // 用户不知道就会重复报，队列不知道就会继续把它当待办。
                String where = (o.getSlotNo() == null ? "格口" : "格口 " + o.getSlotNo())
                        + "（" + o.getTitle() + "）";
                notices.emit(o.getTenantId(),
                        com.wherelee.cabinet.notify.api.NoticePort.CHANNEL_MESSAGE,
                        "WORK_ORDER_AUTO_CLOSED", "WO" + o.getId() + ":AUTO_CLOSE:MSG",
                        "你的工单" + where + " 已因异常解除自动关闭。如果问题仍存在，"
                                + "可在工单详情里点“还没解决”。",
                        o.getCustomerId(), o.getId(), o.getOrderNo());
                notices.emit(o.getTenantId(),
                        com.wherelee.cabinet.notify.api.NoticePort.CHANNEL_QUEUE,
                        "WORK_ORDER_AUTO_CLOSED", "WO" + o.getId() + ":AUTO_CLOSE:QUEUE",
                        "工单 WO" + o.getId() + " 因事实恢复自动关闭：" + where,
                        null, o.getId(), o.getOrderNo());
            }
            log.info("工单自动转化留底 slotId={} type={} 条数={}", slotId, type, closed);
        }
        return closed;
    }

    /**
     * 关闭依据必须能被这张单自己的事实支撑，不能由操作者挑一个“好看的理由”：
     * <ul>
     *   <li>USER_CONFIRMED 只能由用户在小程序点，业务员代写就是伪造用户结论；</li>
     *   <li>FACT_RECOVERED 必须有格口异常已解除的现场事实，否则只是业务员自己的判断；</li>
     *   <li>MERGED / CANCELLED 不在“处理反馈”这条路上发生。</li>
     * </ul>
     * 不满足时统一退回 MANUAL：有结论、有处理人，但那是人的判断，不冒充用户或设备。
     */
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

    /**
     * 用户在小程序确认“问题已解决”：关闭依据写 USER_CONFIRMED。
     * 只对本人、还没关闭的单生效；重复点不重复写（幂等），也不给别人的单开旁路。
     */
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

    /**
     * 用户反馈“还没解决”：重开本人已关闭的单，reopened_count + 1。
     *
     * <p>同格口同队列只允许一张未关闭单：重开撞上唯一索引时并入那张，记 MERGED——
     * 不因为索引冲突就把用户的反馈丢掉，也不制造第二张重复待办。
     *
     * @return 重开后的单号；合并时返回并入的那张
     */
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

    /** 用户侧“我的工单”：最近若干张，不返回内部交接与指派细节。 */
    public List<CustomerView> myOrders(Long customerId, int limit) {
        int size = Math.min(Math.max(limit, 1), 50);
        return orders.selectList(Wrappers.<BizWorkOrder>lambdaQuery()
                        .eq(BizWorkOrder::getCustomerId, customerId)
                        .eq(BizWorkOrder::getDeleted, 0)
                        .orderByDesc(BizWorkOrder::getCreateTime)
                        .last("limit " + size))
                .stream().map(this::toCustomerView).toList();
    }

    /** 用户侧单张工单：只能看自己的，避免拿 id 猜别人的单。 */
    public CustomerView myOrder(Long workOrderId, Long customerId) {
        BizWorkOrder order = journal.require(workOrderId);
        if (customerId == null || !customerId.equals(order.getCustomerId())) {
            throw new BizException(ResultCode.FORBIDDEN, "这张工单不属于当前账号");
        }
        return toCustomerView(order);
    }

    /** 允许前端自定义排序的字段（PageSupport 白名单）：开单时间与要求处理时刻。 */
    private static final Set<String> QUEUE_SORTABLE = Set.of("createTime", "dueAt");

    /**
     * 业务员主动记录一次电话联系（第 21 刀，用户定：系统不强制派电话任务，但打了要留痕）。
     *
     * <p>与 {@code escalate} 自动产的 PHONE_CALL 待办分工清楚：那个是"系统要求必须打"，
     * 这个是"我自己判断要打，结果是什么"——写进事件流水而不是待办清单，
     * 两类记录混在一起就分不清哪些欠着、哪些是已完成的历史。
     */
    @Transactional
    public void notePhoneCall(Long workOrderId, Long operatorId, String resultNote) {
        if (resultNote == null || resultNote.isBlank()) {
            throw new BizException(ResultCode.BIZ_ERROR, "联系结果必填：联系上了/未接/约定多久处理");
        }
        BizWorkOrder order = journal.require(workOrderId);
        journal.note(order, "PHONE_CALLED", operatorId, journal.truncate(resultNote));
    }

    /**
     * 队列分页：紧急在前、开单时间在前的先办（兜底排序，用户传了 orderBy 再追加）。
     *
     * <p>原来这里是写死的 {@code limit 200}——第 201 张单在队列里永远看不到，
     * 而项目的统一分页约定（PageQuery/PageResult）早已立好，这里补齐而不是另造一套。
     */
    public PageResult<View> queue(String queueSide, boolean openOnly, PageQuery query) {
        Page<BizWorkOrder> page = PageSupport.toPage(query, QUEUE_SORTABLE);
        return PageSupport.toResult(orders.selectPage(page, Wrappers.<BizWorkOrder>lambdaQuery()
                .eq(BizWorkOrder::getQueueSide, queueSide)
                .eq(BizWorkOrder::getDeleted, 0)
                // 未关闭用 active_flag 而不是 status 列表：它就是这个口径的结构化定义
                // （开单写 1、关闭置 NULL），并作为等值前缀命中
                // idx_wo_queue(tenant_id, queue_side, active_flag, priority, create_time)，
                // 让过滤与排序走同一棵索引，不再 filesort。
                .eq(openOnly, BizWorkOrder::getActiveFlag, 1)
                .orderByAsc(BizWorkOrder::getPriority)
                .orderByAsc(BizWorkOrder::getCreateTime)), this::toView);
    }

    /**
     * 单张工单视图（第 23 刀：AI 简报工具的数据源；详情类端点未来也可走它）。
     * <p>注意 {@code customerPhone} 在这里只给打电话的人看：AI 简报的工具层负责把它滤掉，
     * 不让电话进模型上下文。
     */
    public View one(Long workOrderId) {
        BizWorkOrder order = journal.require(workOrderId);
        String parentNote = null;
        if (order.getParentId() != null) {
            BizWorkOrder parent = orders.selectById(order.getParentId());
            parentNote = parent == null ? null : parent.getResultNote();
        }
        return toView(order, parentNote);
    }

    /**
     /** 父子单关系：详情页要能双向跳转，且子单看得到父单结论。 */
     public record Family(View self, View parent, List<View> children) {
     }
     
     public Family family(Long workOrderId) {
         BizWorkOrder order = journal.require(workOrderId);
         View self = toView(order, null);
         View parent = order.getParentId() == null ? null : one(order.getParentId());
         List<View> children = orders.selectList(Wrappers.<BizWorkOrder>lambdaQuery()
                         .eq(BizWorkOrder::getParentId, workOrderId)
                         .orderByAsc(BizWorkOrder::getCreateTime))
                 .stream()
                 .map(child -> toView(child, order.getResultNote()))
                 .toList();
         return new Family(self, parent, children);
     }
     
     /** 同格口同类型、已人工处理完结的历史工单（第 23 刀：AI 简报“以前怎么处理的”唯一数据源）。
     * <p>agent 不得杜撰相似案例——所有“以前怎么处理”都从这里来；没有就空着，
     * 而不是拿别的格口/别的类型的单充数。结果按完结时刻倒序：最近的处理方式最值得参考。
     */
    public List<WorkOrderBriefPort.SimilarCase> similarResolved(Long workOrderId, int limit) {
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
                .map(o -> new WorkOrderBriefPort.SimilarCase(o.getTitle(),
                        o.getResultNote() == null ? "" : o.getResultNote(),
                        o.getClosedAt() == null ? null : o.getClosedAt().toLocalDate().toString()))
                .toList();
    }

    public List<EventView> timeline(Long workOrderId) {
        return events.selectList(Wrappers.<BizWorkOrderEvent>lambdaQuery()
                .eq(BizWorkOrderEvent::getWorkOrderId, workOrderId)
                .orderByAsc(BizWorkOrderEvent::getCreateTime))
                .stream()
                .map(e -> new EventView(e.getEventType(), e.getActorType(), e.getOperatorId(),
                        e.getTraceId(), e.getDetail(), e.getCreateTime()))
                .toList();
    }

    /** 异常 → 工单映射所需的类型入口（阶梯升级时要升级同一类单）。 */
    public Long openIdOf(Long slotId, WorkOrderType type) {
        BizWorkOrder open = journal.findOpen(type, slotId);
        return open == null ? null : open.getId();
    }

    /**
     * 按格口取当前绑定的活动单（阶梯里用来区分“订单中”与“结束后”的措辞）。 */
    public Long boundOrderId(Long slotId) {
        BizWorkOrder open = journal.findOpen(WorkOrderType.DOOR_NOT_CLOSED, slotId);
        return open == null ? null : open.getStorageId();
    }

    /**
     * 系统派生的线下工单（用户定：超 72 小时线下运维可介入）。
     *
     * <p>它<b>不碰线上父单</b>：父单转 RESOLVED 意味着“有人做了判断”，而这里是时间到了自动派的，
     * 机器没有资格替人下那个结论。父单继续开着，“谁看过、什么时候派的”记在子单自的事件上。
     *
     * <p>幂等：同一格口同类型在线下队列里只能有一张未关闭的单（唯一索引），
     * 巡检每小时都会跑到这里，不靠索引就会堆一串一样的派单。
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Long openFieldTwin(Long parentWorkOrderId, String reason) {
        BizWorkOrder parent = journal.require(parentWorkOrderId);
        // 父单自己就是 FIELD 单时不能再派一张“线下子单给自己”：同队列唯一索引会挡住
        if (SIDE_FIELD.equals(parent.getQueueSide())) {
            return parent.getId();
        }
        BizWorkOrder existing = journal.findOpenInQueue(parent.getKind(), parent.getType(), parent.getSlotId(), SIDE_FIELD);
        if (existing != null) {
            return existing.getId();
        }
        BizWorkOrder child = new BizWorkOrder();
        child.setTenantId(parent.getTenantId());
        child.setKind(kindOf(parent.getKind()));
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

    /**
     * 在“当前那张未关闭的单”上记一步事实（19D）。
     *
     * <p>与 {@link #remind} 分开：“提醒”是系统催办事，而这一步是“外部发生了什么”
     * （比如另一位用户顺手把门关了）。混成一类事后就分不出“我们做了什么”与“现场发生了什么”。
     */
    @Transactional
    public void noteStep(Long slotId, WorkOrderType type, String eventType, String detail) {
        BizWorkOrder open = journal.findOpen(type, slotId);
        if (open != null) {
            journal.note(open, eventType, null, detail);
        }
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
    private String snapshot(NewOrderV2 cmd) {
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

    private View toView(BizWorkOrder o) {
        return toView(o, null);
    }

    private CustomerView toCustomerView(BizWorkOrder o) {
        return new CustomerView(o.getId(), o.getOrderNo(), o.getTitle(), o.getStatus().name(),
                o.getCategory() == null ? null : o.getCategory().name(),
                o.getPriority() == null ? null : o.getPriority().name(),
                o.getCloseReason() == null ? null : o.getCloseReason().name(),
                o.getResultNote(), o.getSlotNo(), journal.readSnapshot(o, "cabinetNo"),
                journal.readSnapshot(o, "siteAddress"), o.getResponseDueAt(), o.getResolveDueAt(),
                o.getClosedAt(), o.getCreateTime(), o.getMergedIntoId(),
                o.getReopenedCount() == null ? 0 : o.getReopenedCount());
    }

    private View toView(BizWorkOrder o, String parentResultNote) {
        // 柜机编号/点位地址/检测值必须从快照带出来：业务员接了单要能凭这一行知道去哪、看什么，
        // 而不是只看到一句“格口 S12 门未关”就出发（这三个字段一直在快照里，只是以前没读）
        return new View(o.getId(),
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
                journal.readSnapshot(o, "customerPhone"), o.getTitle(),
                journal.readSnapshot(o, "detection"), o.getResultNote(),
                o.getCloseReason() == null ? null : o.getCloseReason().name(),
                o.getAssigneeId(), o.getParentId(), parentResultNote,
                o.getDueAt(), o.getResponseDueAt(), o.getResolveDueAt(), o.getFirstResponseAt(),
                o.getClosedAt(), o.getCreateTime(), o.getHandoffJson(),
                o.getReopenedCount() == null ? 0 : o.getReopenedCount());
    }

}
