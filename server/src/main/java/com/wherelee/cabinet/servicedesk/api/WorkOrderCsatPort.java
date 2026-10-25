package com.wherelee.cabinet.servicedesk.api;

import java.time.LocalDateTime;

public interface WorkOrderCsatPort {

    record CsatView(Long workOrderId, int score, String comment, LocalDateTime at) {
    }

    CsatView submit(Long customerId, Long workOrderId, int score, String comment);

    CsatView byOrder(Long workOrderId);
}
