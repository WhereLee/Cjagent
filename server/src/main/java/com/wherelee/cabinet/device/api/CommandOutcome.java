package com.wherelee.cabinet.device.api;

public record CommandOutcome(Long commandId,
                             CommandState state,
                             boolean businessSuccess,
                             boolean stale,
                             FaultType faultType,
                             String message) {
}
