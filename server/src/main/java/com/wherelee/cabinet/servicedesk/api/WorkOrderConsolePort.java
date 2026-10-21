package com.wherelee.cabinet.servicedesk.api;

import com.wherelee.cabinet.common.api.PageQuery;
import com.wherelee.cabinet.common.api.PageResult;
import com.wherelee.cabinet.domain.enums.CloseReason;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 工单的对外入口：后台队列、用户侧工单、流转动作与读侧视图。
 *
 * <p>为什么把视图记录也放在这里：它们原来内嵌在 {@code WorkOrderService}，BFF 为了写
 * {@code R<...View>} 只能依赖应用类。记录<b>原字段、原名</b>搬进来，OpenAPI 的 schema
 * 名与字段都不变（契约快照机检），调用方换的只是依赖的类型。
 *
 * <p>这是<b>契约层</b>：方法体内的一切流转规则仍在 servicedesk 的 app 层；
 * 端口只负责“有哪些动作、进出什么形状”。
 */
public interface WorkOrderConsolePort {

    /** 一条证据的可读视图（采集点 + 三值结论 + 置信度 + AI 标签 + 照片引用）。 */
    record EvidenceView(String moment, String source, String presence, BigDecimal confidence,
                        String aiLabel, String photoRef, String note, LocalDateTime at) {
    }

    /**
     * 开单入参。<b>所有字段都是“当时看到的事实”</b>，不是引用：电话与格口编号必须快照，
     * 否则用户改绑手机之后，这张单就再也说不清当时联系的是谁。
     */
    record NewOrder(Long tenantId, WorkOrderType type, boolean urgent,
                    Long siteId, Long cabinetId, Long slotId, String slotNo,
                    String orderNo, Long storageId, Long customerId, String customerPhone,
                    String siteAddress, String cabinetNo, String title, String detection,
                    LocalDateTime dueAt) {
    }

    /** 智能工单入参：在旧 NewOrder 上增加 kind/source/intent/category/priority/SLA/交接。 */
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

    /** 队列视图：接口层不回传实体（实体带着一堆流转细节）。 */
    record View(Long id, String kind, String source, String type, String intent, String category,
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
    record EventView(String eventType, String actorType, Long operatorId, String traceId,
                     String detail, LocalDateTime at) {
    }

    /** 父子单关系：详情页要能双向跳转，且子单看得到父单结论。 */
    record Family(View self, View parent, List<View> children) {
    }

    /**
     * 用户侧工单视图：只给用户自己关心的字段。
     * 交接 JSON、快照电话、内部指派这些是给业务员看的，不能顺手塞进小程序。
     */
    record CustomerView(Long id, String orderNo, String title, String status, String category,
                        String priority, String closeReason, String resultNote, String slotNo,
                        String cabinetNo, String siteAddress, LocalDateTime responseDueAt,
                        LocalDateTime resolveDueAt, LocalDateTime closedAt, LocalDateTime createTime,
                        Long mergedIntoId, int reopenedCount) {
    }

    /** 一条相似案例：当初的标题、处理结论原文、完结日期。只引用，不改写。 */
    record SimilarCase(String title, String resultNote, String resolvedAt) {
    }

    // ---- 写侧 ----

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

    void resumeSla(Long workOrderId, Long operatorId);

    Long openIdOf(Long slotId, WorkOrderType type);

    Long boundOrderId(Long slotId);

    void noteStep(Long slotId, WorkOrderType type, String eventType, String detail);

    // ---- 读侧 ----

    PageResult<View> queue(String queueSide, boolean openOnly, PageQuery query);

    View one(Long workOrderId);

    Family family(Long workOrderId);

    List<SimilarCase> similarResolved(Long workOrderId, int limit);

    List<EventView> timeline(Long workOrderId);

    List<EvidenceView> evidenceFor(Long workOrderId);

    List<CustomerView> myOrders(Long customerId, int limit);

    CustomerView myOrder(Long workOrderId, Long customerId);
}
