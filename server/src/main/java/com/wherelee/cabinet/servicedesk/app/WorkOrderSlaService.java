package com.wherelee.cabinet.servicedesk.app;

import com.wherelee.cabinet.notify.api.NoticeChannel;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.platform.scheduling.DelayTaskService;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.enums.TaskType;
import com.wherelee.cabinet.notify.api.NoticePort;
import com.wherelee.cabinet.servicedesk.api.WorkOrderType;
import com.wherelee.cabinet.servicedesk.domain.BizWorkOrder;
import com.wherelee.cabinet.servicedesk.domain.BizWorkOrderEvent;
import com.wherelee.cabinet.servicedesk.infra.BizWorkOrderEventMapper;
import com.wherelee.cabinet.servicedesk.infra.BizWorkOrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 工单的时限与催办：两个 SLA 时限的登记/暂停/恢复，以及“门未关阶梯”的催办与升级。
 *
 * <p>为什么从生命周期里拆出来：开单、认领、处理、关闭讲的是“谁做了什么判断”，
 * 而时限与催办讲的是“到点没做到怎么办”。后者有自己的一套时间算术（暂停补时、阶梯计数），
 * 混在 1000 行的生命周期类里时，任何一次改时限都要先读懂全流程。
 */
@Service
public class WorkOrderSlaService {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderSlaService.class);

    private final WorkOrderJournal journal;
    private final BizWorkOrderMapper orders;
    private final BizWorkOrderEventMapper events;
    private final NoticePort notices;
    /** 延迟任务用 ObjectProvider 取：任务处理器反过来依赖工单服务，直接注入会形成构造环。 */
    private final ObjectProvider<DelayTaskService> schedulerProvider;

    public WorkOrderSlaService(WorkOrderJournal journal, BizWorkOrderMapper orders,
                               BizWorkOrderEventMapper events, NoticePort notices,
                               ObjectProvider<DelayTaskService> schedulerProvider) {
        this.journal = journal;
        this.orders = orders;
        this.events = events;
        this.notices = notices;
        this.schedulerProvider = schedulerProvider;
    }

    /**
     * 暂停 SLA：只停两个时限的前进，不改工单状态。暂停中取消两个到点任务，
     * 恢复时按实际暂停时长把时限补回来——否则“等配件”停的一周会把 SLA 达成率吃掉。
     */
    @Transactional
    public void pause(Long workOrderId, Long operatorId) {
        BizWorkOrder order = journal.require(workOrderId);
        if (!journal.isOpen(order)) {
            throw new BizException(ResultCode.BIZ_ERROR, "工单已关闭，不能暂停 SLA");
        }
        if (order.getSlaPausedAt() != null) {
            return; // 重放友好：已暂停就不重复写
        }
        orders.update(null, Wrappers.<BizWorkOrder>lambdaUpdate()
                .eq(BizWorkOrder::getId, workOrderId)
                .isNull(BizWorkOrder::getSlaPausedAt)
                .set(BizWorkOrder::getSlaPausedAt, LocalDateTime.now()));
        cancel(order);
        journal.note(order, "SLA_PAUSED", operatorId, "SLA 已暂停（暂停时长不计入时限）");
    }

    /** 恢复 SLA：把暂停时长补回响应/解决时限，并重新登记到点任务。 */
    @Transactional
    public void resume(Long workOrderId, Long operatorId) {
        BizWorkOrder order = journal.require(workOrderId);
        if (order.getSlaPausedAt() == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        long pausedMinutes = Math.max(0, Duration.between(order.getSlaPausedAt(), now).toMinutes());
        LocalDateTime responseDue = order.getResponseDueAt() == null
                ? null : order.getResponseDueAt().plusMinutes(pausedMinutes);
        LocalDateTime resolveDue = order.getResolveDueAt() == null
                ? null : order.getResolveDueAt().plusMinutes(pausedMinutes);
        int accumulated = (order.getSlaPausedMinutes() == null ? 0 : order.getSlaPausedMinutes())
                + (int) pausedMinutes;
        orders.update(null, Wrappers.<BizWorkOrder>lambdaUpdate()
                .eq(BizWorkOrder::getId, workOrderId)
                .set(BizWorkOrder::getSlaPausedAt, null)
                .set(BizWorkOrder::getSlaPausedMinutes, accumulated)
                .set(BizWorkOrder::getResponseDueAt, responseDue)
                .set(BizWorkOrder::getResolveDueAt, resolveDue)
                .set(BizWorkOrder::getDueAt, resolveDue));
        // 取消时任务落成 DONE，register 会把它当作新一轮重新排上。
        order.setSlaPausedAt(null);
        order.setResponseDueAt(responseDue);
        order.setResolveDueAt(resolveDue);
        schedule(order);
        journal.note(order, "SLA_RESUMED", operatorId, "SLA 已恢复：暂停 " + pausedMinutes + " 分钟已补回时限");
    }

    /** 登记两个到点任务（响应时限与解决时限）。 */
    public void schedule(BizWorkOrder order) {
        if (order == null || order.getId() == null || order.getTenantId() == null || !journal.isOpen(order)) {
            return;
        }
        String bizKey = String.valueOf(order.getId());
        if (order.getFirstResponseAt() == null && order.getResponseDueAt() != null) {
            scheduler().schedule(TaskType.WORK_ORDER_RESPONSE_SLA, bizKey,
                    order.getTenantId(), order.getResponseDueAt());
        }
        if (order.getResolveDueAt() != null) {
            scheduler().schedule(TaskType.WORK_ORDER_RESOLVE_SLA, bizKey,
                    order.getTenantId(), order.getResolveDueAt());
        }
    }

    /** 取消两个到点任务（暂停或关闭时）。 */
    public void cancel(BizWorkOrder order) {
        String bizKey = String.valueOf(order.getId());
        scheduler().cancel(TaskType.WORK_ORDER_RESPONSE_SLA, bizKey, order.getTenantId());
        scheduler().cancel(TaskType.WORK_ORDER_RESOLVE_SLA, bizKey, order.getTenantId());
    }

    /** 只取消响应时限任务（认领时用：首响已经发生）。 */
    public void cancelResponse(BizWorkOrder order) {
        scheduler().cancel(TaskType.WORK_ORDER_RESPONSE_SLA, String.valueOf(order.getId()),
                order.getTenantId());
    }

    private DelayTaskService scheduler() {
        return schedulerProvider.getObject();
    }

    /**
     * 记一次“被催办”（19D 阶梯）。返回 false 表示没有未关闭的对应工单（异常已收口）。
     *
     * <p>为什么不开一张 {@code biz_reminder} 表：提醒的语义就是“这张单又被催了一次”，
     * 它本来就是工单事件流水里的一行；另建一张表只会多出一个要同步的真相。
     * 真正的投递（站内消息、需打电话的任务）是另一件事，落在 {@code biz_notice} 上。
     */
    @Transactional
    public boolean remind(Long slotId, WorkOrderType type, String detail) {
        BizWorkOrder open = journal.findOpen(type, slotId);
        if (open == null) {
            return false;
        }
        journal.note(open, "REMINDER", null, detail);
        return true;
    }

    /**
     * 阶梯到点：升级为紧急并把处理时限拉到“就是现在”，同时产出两件可见的东西：
     * 业务员一条“必须电话联系客户”的待办，客户一条站内消息。
     *
     * <p>去重键用“工单 ID + 档位”而不是“时刻”：阶梯每轮巡检都会重进这里，
     * 用时刻做键就会每隔几分钟刷出一条一模一样的电话任务。
     */
    @Transactional
    public boolean escalate(Long slotId, WorkOrderType type, String detail) {
        if (orders.escalateBySlot(slotId, type.name()) == 0) {
            return false;
        }
        BizWorkOrder open = journal.findOpen(type, slotId);
        if (open == null) {
            return true;
        }
        journal.note(open, "ESCALATED", null, detail);
        String phone = journal.readSnapshot(open, "customerPhone");
        String where = (open.getSlotNo() == null ? "格口" : "格口 " + open.getSlotNo())
                + "（" + open.getTitle() + "）";
        // 业务员侧待办：不假装系统自己打了电话，只把“必须联系”挂在队列里并要求回填结果
        notices.emit(open.getTenantId(), NoticeChannel.PHONE_CALL,
                type.name(), "WO" + open.getId() + ":PHONE",
                "必须电话联系客户 " + (phone == null ? "（无电话记录）" : phone) + "：" + where + " — " + detail,
                open.getCustomerId(), open.getId(), open.getOrderNo());
        // 客户侧站内消息：没有推送，他自己打开小程序能看到也算提醒到了
        notices.emit(open.getTenantId(), NoticeChannel.SYSTEM_MESSAGE,
                type.name(), "WO" + open.getId() + ":MSG",
                "你的暂存柜" + where + " 需要处理：" + detail + "。请尽快关门或取走物品并结束订单。",
                open.getCustomerId(), open.getId(), open.getOrderNo());
        log.warn("工单升级紧急 slotId={} type={} {}", slotId, type, detail);
        return true;
    }

    /**
     * 这张单已催过几次。阶梯靠它判重：一轮巡检可能跑两次（租约过期被接管），
     * 不数已发过多少就会在同一时刻重复发提醒。
     */
    public int remindedTimes(Long slotId, WorkOrderType type) {
        BizWorkOrder open = journal.findOpen(type, slotId);
        if (open == null) {
            return 0;
        }
        return Math.toIntExact(events.selectCount(Wrappers.<BizWorkOrderEvent>lambdaQuery()
                .eq(BizWorkOrderEvent::getWorkOrderId, open.getId())
                .eq(BizWorkOrderEvent::getEventType, "REMINDER")));
    }
}
