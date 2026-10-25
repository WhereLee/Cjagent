package com.wherelee.cabinet.locker.api;

import java.time.LocalDateTime;

public record Sensing(boolean doorClosed, Presence presence, LocalDateTime sensedAt) {
}
