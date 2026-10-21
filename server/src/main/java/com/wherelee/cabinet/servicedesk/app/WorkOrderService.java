package com.wherelee.cabinet.servicedesk.app;

import com.wherelee.cabinet.common.api.PageQuery;
import com.wherelee.cabinet.common.api.PageResult;
import com.wherelee.cabinet.domain.enums.CloseReason;
import com.wherelee.cabinet.servicedesk.api.WorkOrderCategory;
import com.wherelee.cabinet.servicedesk.api.WorkOrderImpact;
import com.wherelee.cabinet.servicedesk.api.WorkOrderIntent;
import com.wherelee.cabinet.servicedesk.api.WorkOrderKind;
import com.wherelee.cabinet.servicedesk.api.WorkOrderPriority;
import com.wherelee.cabinet.servicedesk.api.WorkOrderConsolePort;
import com.wherelee.cabinet.servicedesk.api.WorkOrderSource;
import com.wherelee.cabinet.servicedesk.api.WorkOrderType;
import com.wherelee.cabinet.servicedesk.api.WorkOrderUrgency;
import com.wherelee.cabinet.servicedesk.domain.BizWorkOrder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工单用例的门面（19B）：Controller、巡检 handler 与设备侧端口都只认这个类。
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
 *
 * <p>实现已按职责拆开：开单/分诊在 {@link WorkOrderIntakeService}，流转/收口在
 * {@link WorkOrderLifecycleService}，读侧在 {@link WorkOrderQueryService}，
 * 取单与留痕在 {@link WorkOrderJournal}，时限在 {@link WorkOrderSlaService}。
 * 本类保留<b>对外签名与视图记录</b>——记录被 bff 与 assistant 直接引用，挪包会改契约。
 */
@Service
public class WorkOrderService implements WorkOrderConsolePort {

    /** 队列侧常量：两条队列对应两个角色，不靠角色名硬猜。 */
    public static final String SIDE_ONLINE = "ONLINE";
    public static final String SIDE_FIELD = "FIELD";

    /** 取单与事件留痕：生命周期、SLA、查询三处共用同一条流水。 */
    private final WorkOrderJournal journal;
    /** 两个 SLA 时限与门未关阶梯。 */
    private final WorkOrderSlaService sla;
    /** 读侧：队列、视图、留痕、证据。 */
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

    // ------------------------------------------------------------------
    // 写侧：开单/分诊 → intake，流转/收口 → lifecycle
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // 取单与事实留痕
    // ------------------------------------------------------------------

    /** 异常 → 工单映射所需的类型入口（阶梯升级时要升级同一类单）。 */
    public Long openIdOf(Long slotId, WorkOrderType type) {
        BizWorkOrder open = journal.findOpen(type, slotId);
        return open == null ? null : open.getId();
    }

    /**
     * 按格口取当前绑定的活动单（阶梯里用来区分“订单中”与“结束后”的措辞）。
     */
    public Long boundOrderId(Long slotId) {
        BizWorkOrder open = journal.findOpen(WorkOrderType.DOOR_NOT_CLOSED, slotId);
        return open == null ? null : open.getStorageId();
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

    // ------------------------------------------------------------------
    // 读侧：队列与视图装配在 WorkOrderQueryService，这里只保留同名入口
    // ------------------------------------------------------------------

    public java.util.List<EvidenceView> evidenceFor(Long workOrderId) {
        return queries.evidenceFor(workOrderId);
    }

    public PageResult<View> queue(String queueSide, boolean openOnly, PageQuery query) {
        return queries.queue(queueSide, openOnly, query);
    }

    public View one(Long workOrderId) {
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
