package com.wherelee.cabinet.servicedesk.app;

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

/**
 * 工单的流转与收口：认领、处理反馈（含派线下）、事实恢复自动关闭、用户确认/重开、自动派单。
 *
 * <p>为什么拆出来：这一组的共同点是<b>每个动作都要同时写“状态 + 流水 + SLA + 通知”</b>，
 * 而“关闭依据必须被这张单自己的事实支撑”是安全边界（业务员不能代写用户确认）。
 * 与开单（{@link WorkOrderIntakeService}）分开后，两条链各自的失败语义不再混在一起。
 */
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
        child.setKind(order.getKind());
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
                        NoticePort.CHANNEL_MESSAGE,
                        "WORK_ORDER_AUTO_CLOSED", "WO" + o.getId() + ":AUTO_CLOSE:MSG",
                        "你的工单" + where + " 已因异常解除自动关闭。如果问题仍存在，"
                                + "可在工单详情里点“还没解决”。",
                        o.getCustomerId(), o.getId(), o.getOrderNo());
                notices.emit(o.getTenantId(),
                        NoticePort.CHANNEL_QUEUE,
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
