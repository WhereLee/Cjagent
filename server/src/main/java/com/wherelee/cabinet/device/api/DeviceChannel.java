package com.wherelee.cabinet.device.api;

import com.wherelee.cabinet.device.api.CommandAction;
import com.wherelee.cabinet.locker.api.Presence;
import com.wherelee.cabinet.device.api.ReportEvent;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

public interface DeviceChannel {

    CompletableFuture<CommandReceipt> send(CommandTicket ticket);

    boolean available(Long cabinetId);

    SlotSensor probe(Long cabinetId, Long slotId);

    record SlotSensor(boolean doorClosed, Presence presence, LocalDateTime sensedAt) {
    }

    record CommandTicket(String requestId, Long cabinetId, Long slotId, CommandAction action) {
    }

    record CommandReceipt(String requestId,
                          boolean success,
                          ReportEvent event,
                          long seq,
                          boolean sensorConfirmed,
                          Long executedSlotId,
                          String detail) {
    }
}
