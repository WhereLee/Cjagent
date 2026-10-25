package com.wherelee.cabinet.device.api;

import java.util.List;

public interface CommandPort {

    CommandOutcome dispatch(Long cabinetId, Long slotId, CommandAction action, Long orderId, String requestId);

    long countAttempts(String requestIdPrefix);

    List<CommandSnapshot> byOrder(Long orderId, int limit);

    boolean anyOpenSucceeded(Long orderId);
}
