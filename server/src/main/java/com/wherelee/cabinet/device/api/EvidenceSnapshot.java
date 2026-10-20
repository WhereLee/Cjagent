package com.wherelee.cabinet.device.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 一次物检测量（只读快照）。
 *
 * <p>柜内物检证据是"设备看过什么"的留底：工单详情、运营台、遗留物处置都要读它。
 * 读的人不该知道它存在哪张表，更不该自己写。</p>
 */
public record EvidenceSnapshot(String moment,
                               String source,
                               String presence,
                               BigDecimal confidence,
                               String aiLabel,
                               String photoRef,
                               String note,
                               LocalDateTime at) {
}
