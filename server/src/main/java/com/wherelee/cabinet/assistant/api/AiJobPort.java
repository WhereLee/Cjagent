package com.wherelee.cabinet.assistant.api;

import com.wherelee.cabinet.domain.enums.AiJobType;

import java.time.LocalDateTime;

public interface AiJobPort {

    Long submit(AiJobType jobType, String input, Long requesterId);

    AiJobView view(Long jobId, Long requesterId);

    record AiJobView(Long id, String jobType, String status, Object result, String error,
                LocalDateTime createTime, LocalDateTime updateTime) {
    }
}
