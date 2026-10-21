package com.wherelee.cabinet.servicedesk.api;

import java.time.LocalDateTime;

/**
 * 工单满意度入口：用户确认解决后提交一次评价（1–5 星 + 选填备注）。
 *
 * <p>只记录事实，不参与工单状态迁移；一单一次由数据库唯一索引兜底。
 */
public interface WorkOrderCsatPort {

    record CsatView(Long workOrderId, int score, String comment, LocalDateTime at) {
    }

    CsatView submit(Long customerId, Long workOrderId, int score, String comment);

    /** 这张单的评价；未评价返回 null。 */
    CsatView byOrder(Long workOrderId);
}
