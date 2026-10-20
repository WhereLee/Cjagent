package com.wherelee.cabinet.device.api;

import java.time.LocalDateTime;

/**
 * 指令历史的一行（只读）。
 *
 * <p>action/state 用字符串而不是枚举：运营台只是展示，不需要让前端契约跟着设备枚举走。
 */
public record CommandSnapshot(Long id,
                              String requestId,
                              String action,
                              String state,
                              int retryCount,
                              LocalDateTime sentAt,
                              LocalDateTime ackedAt,
                              String lastError) {
}
