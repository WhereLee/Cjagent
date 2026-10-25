package com.wherelee.cabinet.locker.api;

import com.wherelee.cabinet.notify.api.NoticeChannel;

public interface OutboxPort {

    void appendWorkOrderAnomaly(Long slotId, CompartmentAnomaly anomaly, String reason, String dedupKey);

    void appendOwnerNotice(NoticeChannel channel, String kind, String dedupKey, String content,
                           Long customerId, Long workOrderId, String orderNo);

    record WorkOrderAnomalyPayload(Long slotId, CompartmentAnomaly anomaly, String reason) {
    }

    record OwnerNoticePayload(NoticeChannel channel, String kind, String dedupKey, String content,
                              Long customerId, Long workOrderId, String orderNo) {
    }
}
