package com.wherelee.cabinet.servicedesk.api;

import com.wherelee.cabinet.domain.entity.BizStorageOrder;

import java.time.LocalDateTime;

/**
 * 由订单事实开单的入口（AI 转报、系统任务共用）。
 *
 * <p>为什么单独成端口而不是塞进 {@link WorkOrderConsolePort}：调用方（assistant）只需要
 * “把这次事件开成一张单”，不需要队列、SLA、处置动作。端口越窄，越不会把工单内部规则
 * 顺着一个方法签名带到别的服务里。
 */
public interface WorkOrderIntakePort {

    /**
     * 智能工单入口：AI 转报带 intent/handoff，系统任务传 null。
     *
     * <p>返回工单 ID：新建的返回新 ID，幂等合并的返回已存在那张的 ID。
     */
    Long onOrderEvent(BizStorageOrder order, WorkOrderType type, boolean urgent,
                      String detection, String title, LocalDateTime dueAt,
                      WorkOrderKind kind, WorkOrderSource source, WorkOrderIntent intent,
                      WorkOrderCategory category, WorkOrderPriority priority, String handoffJson,
                      String traceId);
}
