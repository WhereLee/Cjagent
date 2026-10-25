package com.wherelee.cabinet.platform.scheduling;

import com.wherelee.cabinet.domain.enums.TaskType;

import java.time.LocalDateTime;

public interface DelayReminder {

    void remind(TaskType type, String bizKey, LocalDateTime fireAt);

    String mode();
}
