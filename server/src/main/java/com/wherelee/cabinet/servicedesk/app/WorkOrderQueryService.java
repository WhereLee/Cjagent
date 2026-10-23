package com.wherelee.cabinet.servicedesk.app;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wherelee.cabinet.common.api.PageQuery;
import com.wherelee.cabinet.common.api.PageResult;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.device.api.EvidencePort;
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

/**
 * 工单的读侧：队列分页、单张视图、父子关系、留痕、证据、相似案例、用户侧“我的工单”。
 *
 * <p>为什么从生命周期里拆出来：开单/认领/处理/关闭每写一步都在改状态，
 * 而读侧只回答“现在长什么样”。混在一起时，任何一次查询字段调整都要重新理解整条流转；
 * 拆开之后，写侧不再持有任何视图装配逻辑。
 *
 * <p>视图记录仍然挂在 {@link WorkOrderService} 上（那是它们对外暴露的位置），
 * 这里只负责把它们装配出来。
 */
@Service
public class WorkOrderQueryService {

    /** 允许前端自定义排序的字段（PageSupport 白名单）：开单时间与要求处理时刻。 */
    private static final Set<String> QUEUE_SORTABLE = Set.of("createTime", "dueAt");

    private final BizWorkOrderMapper orders;
    private final BizWorkOrderEventMapper events;
    private final EvidencePort evidencePort;
    private final WorkOrderJournal journal;
    private final BizWorkOrderCsatMapper csats;

    public WorkOrderQueryService(BizWorkOrderMapper orders, BizWorkOrderEventMapper events,
                                 EvidencePort evidencePort, WorkOrderJournal journal,
                                 BizWorkOrderCsatMapper csats) {
        this.orders = orders;
        this.events = events;
        this.evidencePort = evidencePort;
        this.journal = journal;
        this.csats = csats;
    }

    /**
     * 队列分页：紧急在前、开单时间在前的先办（兜底排序，用户传了 orderBy 再追加）。
     *
     * <p>原来这里是写死的 {@code limit 200}——第 201 张单在队列里永远看不到，
     * 而项目的统一分页约定（PageQuery/PageResult）早已立好，这里补齐而不是另造一套。
     */
    public PageResult<WorkOrderConsolePort.View> queue(String queueSide, boolean openOnly, PageQuery query) {
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
    public WorkOrderConsolePort.View one(Long workOrderId) {
        BizWorkOrder order = journal.require(workOrderId);
        String parentNote = null;
        if (order.getParentId() != null) {
            BizWorkOrder parent = orders.selectById(order.getParentId());
            parentNote = parent == null ? null : parent.getResultNote();
        }
        return toView(order, parentNote, csatOf(workOrderId));
    }

    /** 父子单关系：详情页要能双向跳转，且子单看得到父单结论。 */
    public WorkOrderConsolePort.Family family(Long workOrderId) {
        BizWorkOrder order = journal.require(workOrderId);
        WorkOrderConsolePort.View self = toView(order, null, csatOf(workOrderId));
        WorkOrderConsolePort.View parent = order.getParentId() == null ? null : one(order.getParentId());
        List<WorkOrderConsolePort.View> children = orders.selectList(Wrappers.<BizWorkOrder>lambdaQuery()
                        .eq(BizWorkOrder::getParentId, workOrderId)
                        .orderByAsc(BizWorkOrder::getCreateTime))
                .stream()
                .map(child -> toView(child, order.getResultNote()))
                .toList();
        return new WorkOrderConsolePort.Family(self, parent, children);
    }

    /** 同格口同类型、已人工处理完结的历史工单（第 23 刀：AI 简报“以前怎么处理的”唯一数据源）。 */
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

    /**
     * 这张工单相关格口的证据时间线（19C）。
     *
     * <p>按格口取而不是按单号取：代关、上报遗留这些事可能根本没有活动单可挂，
     * 而业务员要看的是这个格子先后被看到过什么。
     */
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

    /** 用户侧“我的工单”：最近若干张，不返回内部交接与指派细节。 */
    public List<WorkOrderConsolePort.CustomerView> myOrders(Long customerId, int limit) {
        int size = Math.min(Math.max(limit, 1), 50);
        return orders.selectList(Wrappers.<BizWorkOrder>lambdaQuery()
                        .eq(BizWorkOrder::getCustomerId, customerId)
                        .eq(BizWorkOrder::getDeleted, 0)
                        .orderByDesc(BizWorkOrder::getCreateTime)
                        .last("limit " + size))
                .stream().map(this::toCustomerView).toList();
    }

    /** 用户侧单张工单：只能看自己的，避免拿 id 猜别人的单。 */
    public WorkOrderConsolePort.CustomerView myOrder(Long workOrderId, Long customerId) {
        BizWorkOrder order = journal.require(workOrderId);
        if (customerId == null || !customerId.equals(order.getCustomerId())) {
            throw new BizException(ResultCode.FORBIDDEN, "这张工单不属于当前账号");
        }
        return toCustomerView(order, csatOf(workOrderId));
    }

    private WorkOrderConsolePort.View toView(BizWorkOrder o) {
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

    private WorkOrderConsolePort.View toView(BizWorkOrder o, String parentResultNote) {
        return toView(o, parentResultNote, null);
    }

    private WorkOrderConsolePort.View toView(BizWorkOrder o, String parentResultNote, BizWorkOrderCsat csat) {
        // 柜机编号/点位地址/检测值必须从快照带出来：业务员接了单要能凭这一行知道去哪、看什么，
        // 而不是只看到一句“格口 S12 门未关”就出发（这三个字段一直在快照里，只是以前没读）
        return new WorkOrderConsolePort.View(o.getId(),
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
