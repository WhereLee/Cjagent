package com.wherelee.cabinet.locker.api;

import com.wherelee.cabinet.domain.entity.BizOutbox;

public interface OutboxPublisher {

    void publish(BizOutbox row);
}
