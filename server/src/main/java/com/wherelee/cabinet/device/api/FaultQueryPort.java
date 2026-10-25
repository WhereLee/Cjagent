package com.wherelee.cabinet.device.api;

import java.time.LocalDateTime;
import java.util.List;

public interface FaultQueryPort {

    List<FaultSnapshot> recent(int limit);

    record FaultSnapshot(Long slotId, String action, LocalDateTime at) {
    }
}
