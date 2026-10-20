package com.wherelee.cabinet.locker.api;

import java.time.LocalDateTime;

/** 一次设备问答的结果：门关没关、里面有没有东西、什么时候问的。 */
public record Sensing(boolean doorClosed, Presence presence, LocalDateTime sensedAt) {
}
