package com.wherelee.cabinet.device.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record EvidenceSnapshot(String moment,
                               String source,
                               String presence,
                               BigDecimal confidence,
                               String aiLabel,
                               String photoRef,
                               String note,
                               LocalDateTime at) {
}
