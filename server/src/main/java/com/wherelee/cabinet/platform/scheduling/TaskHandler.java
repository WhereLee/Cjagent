package com.wherelee.cabinet.platform.scheduling;

import com.wherelee.cabinet.domain.entity.BizDelayTask;
import com.wherelee.cabinet.domain.enums.TaskType;

public interface TaskHandler {

    TaskType type();

    default boolean transactional() {
        return true;
    }

    boolean handle(BizDelayTask task);
}
