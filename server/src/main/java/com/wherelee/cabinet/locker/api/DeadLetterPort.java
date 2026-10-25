package com.wherelee.cabinet.locker.api;

import com.wherelee.cabinet.common.api.PageQuery;
import com.wherelee.cabinet.common.api.PageResult;

import java.time.LocalDateTime;

public interface DeadLetterPort {

    void archive(String topic, String tag, String dlqTopic, Long tenantId,
                 String msgKey, String payload, String error, int reconsumeTimes);

    boolean replay(Long id);

    PageResult<DeadLetterView> list(String status, PageQuery query);

    record DeadLetterView(Long id, String topic, String tag, String dlqTopic, String msgKey, String payload,
                          String error, int reconsumeTimes, String status,
                          LocalDateTime receivedAt, LocalDateTime replayedAt) {
    }
}
