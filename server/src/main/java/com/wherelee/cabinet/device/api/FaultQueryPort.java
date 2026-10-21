package com.wherelee.cabinet.device.api;

import java.time.LocalDateTime;
import java.util.List;

/** 故障台账只读端口：运维 Copilot 回答“最近出过什么故障”只需要台账末尾几行。 */
public interface FaultQueryPort {

    /** 最近的事件（新→旧，最多 limit 条）。 */
    List<FaultSnapshot> recent(int limit);

    record FaultSnapshot(Long slotId, String action, LocalDateTime at) {
    }
}
