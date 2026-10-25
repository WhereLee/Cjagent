package com.wherelee.cabinet.notify.api;

import java.time.LocalDateTime;
import java.util.List;

public interface NoticePort {

    record NoticeView(Long id, String channel, String kind, String content, String orderNo,
                Long workOrderId, LocalDateTime createTime, LocalDateTime doneAt,
                String doneNote) {
    }

    boolean emit(Long tenantId, NoticeChannel channel, String kind, String dedupKey, String content,
                 Long customerId, Long workOrderId, String orderNo);

    void rewrite(String dedupKey, String content);

    List<NoticeView> forCustomer(Long customerId);

    List<NoticeView> pendingCalls();

    void markRead(Long id, Long customerId);

    void markCallDone(Long id, String note);
}
