package com.wherelee.cabinet.device.api;

import java.time.LocalDateTime;

public record CommandSnapshot(Long id,
                              String requestId,
                              String action,
                              String state,
                              int retryCount,
                              LocalDateTime sentAt,
                              LocalDateTime ackedAt,
                              String lastError) {
}
