package com.wherelee.cabinet.device.infra;

import com.wherelee.cabinet.device.api.DeviceChannel;
import com.wherelee.cabinet.device.api.CommandAction;
import com.wherelee.cabinet.locker.api.Presence;
import com.wherelee.cabinet.device.api.ReportEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
@Profile({"dev", "test", "integration"})
public class SimulatedCabinetChannel implements DeviceChannel {

    private static final Logger log = LoggerFactory.getLogger(SimulatedCabinetChannel.class);

    public enum Fault {
        NORMAL,
        NO_RECEIPT,
        OPEN_NO_RECEIPT,
        PREPARE_FAIL,
        LYING_CLOSED,
        LYING_OPENED,
        WRONG_TARGET,
        STALE_SEQ,
        EXEC_FAIL,
        OFFLINE,

        SENSOR_STUCK_OPEN,
        SENSOR_NO_ITEM,
        ITEM_UNDETECTED
    }

    private static final long WRONG_TARGET_OFFSET = 900001L;

    private static final long STALE_SEQ_VALUE = 0L;

    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "cabinet-simulator");
        thread.setDaemon(true);
        return thread;
    });

    private final Map<String, AtomicLong> seqBySlot = new ConcurrentHashMap<>();

    private final Map<String, Boolean> doorClosedBySlot = new ConcurrentHashMap<>();

    private final Map<String, Boolean> itemInsideBySlot = new ConcurrentHashMap<>();

    private static final int IDEMPOTENCY_CACHE = 512;

    private final Map<String, CommandReceipt> receiptByRequestId = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CommandReceipt> eldest) {
                    return size() > IDEMPOTENCY_CACHE;
                }
            });

    private volatile Fault defaultFault = Fault.NORMAL;

    private final Map<Long, Fault> faultByCabinet = new ConcurrentHashMap<>();

    @Value("${cabinet.simulator.reply-delay-ms:60}")
    private volatile long replyDelayMs;

    @Override
    public CompletableFuture<CommandReceipt> send(CommandTicket ticket) {
        CommandReceipt remembered = receiptByRequestId.get(ticket.requestId());
        if (remembered != null) {
            log.info("模拟器命中设备端幂等表，返回首次结果 requestId={} success={}",
                    ticket.requestId(), remembered.success());
            return CompletableFuture.completedFuture(remembered);
        }
        Fault fault = effectiveFault(ticket.cabinetId());
        if (fault == Fault.OFFLINE) {
            return new CompletableFuture<>();
        }
        if (fault == Fault.NO_RECEIPT) {
            return new CompletableFuture<>();
        }
        if (fault == Fault.OPEN_NO_RECEIPT && ticket.action() != CommandAction.PREPARE) {
            return new CompletableFuture<>();
        }

        long seq = nextSeq(ticket.cabinetId(), ticket.slotId());
        CompletableFuture<CommandReceipt> future = new CompletableFuture<>();
        Runnable reply = () -> {
            applyPhysics(ticket, fault);
            CommandReceipt receipt = receiptFor(fault, ticket, seq);
            receiptByRequestId.put(ticket.requestId(), receipt);
            future.complete(receipt);
        };
        if (replyDelayMs <= 0) {
            reply.run();
        } else {
            executor.schedule(reply, replyDelayMs, TimeUnit.MILLISECONDS);
        }
        return future;
    }

    private CommandReceipt receiptFor(Fault fault, CommandTicket ticket, long seq) {
        if (fault == Fault.PREPARE_FAIL && ticket.action() == CommandAction.PREPARE) {
            return new CommandReceipt(ticket.requestId(), false, ReportEvent.FAULT,
                    seq, true, ticket.slotId(), "预备开未通过：锁机构无动作");
        }
        return switch (fault) {
            case LYING_OPENED -> new CommandReceipt(ticket.requestId(), true, ReportEvent.DOOR_OPENED,
                    seq, true, ticket.slotId(), "设备称已开门，门磁未动作");
            case LYING_CLOSED -> new CommandReceipt(ticket.requestId(), true, ReportEvent.DOOR_CLOSED,
                    seq, false, ticket.slotId(), "设备称已关门，门磁未确认");
            case WRONG_TARGET -> new CommandReceipt(ticket.requestId(), true, ReportEvent.DOOR_OPENED,
                    seq, true, ticket.slotId() + WRONG_TARGET_OFFSET, "回执格口与指令不符");
            case STALE_SEQ -> new CommandReceipt(ticket.requestId(), true, ReportEvent.DOOR_OPENED,
                    STALE_SEQ_VALUE, true, ticket.slotId(), "序号早于已记录（乱序重放）");
            case EXEC_FAIL -> new CommandReceipt(ticket.requestId(), false, ReportEvent.FAULT,
                    seq, true, ticket.slotId(), "电机阻塞，开柜失败");
            default -> {
                boolean opening = ticket.action() == CommandAction.OPEN
                        || ticket.action() == CommandAction.OPEN_TEMP;
                boolean sensorOk = ticket.action() != CommandAction.CLOSE_VERIFY
                        || sensorDoorClosed(ticket.cabinetId(), ticket.slotId(), fault);
                if (ticket.action() == CommandAction.PREPARE) {
                    yield new CommandReceipt(ticket.requestId(), true, ReportEvent.HEARTBEAT,
                            seq, true, ticket.slotId(), null);
                }
                yield new CommandReceipt(ticket.requestId(), true,
                        opening ? ReportEvent.DOOR_OPENED : ReportEvent.DOOR_CLOSED,
                        seq, sensorOk, ticket.slotId(),
                        sensorOk ? null : "门磁未确认关闭（传感器卡住，或门确实没关）");
            }
        };
    }

    private void applyPhysics(CommandTicket ticket, Fault fault) {
        if (ticket.action() == CommandAction.PREPARE) {
            return;
        }
        switch (fault) {
            case OFFLINE, NO_RECEIPT, EXEC_FAIL, LYING_OPENED -> {
            }
            case WRONG_TARGET -> setDoor(ticket.cabinetId(), ticket.slotId() + WRONG_TARGET_OFFSET, ticket.action());
            case LYING_CLOSED -> {
                boolean opening = ticket.action() == CommandAction.OPEN || ticket.action() == CommandAction.OPEN_TEMP;
                if (opening) {
                    setDoor(ticket.cabinetId(), ticket.slotId(), ticket.action());
                }
            }
            default -> setDoor(ticket.cabinetId(), ticket.slotId(), ticket.action());
        }
    }

    private void setDoor(Long cabinetId, Long slotId, CommandAction action) {
        boolean opening = action == CommandAction.OPEN || action == CommandAction.OPEN_TEMP;
        doorClosedBySlot.put(key(cabinetId, slotId), !opening);
    }

    private boolean sensorDoorClosed(Long cabinetId, Long slotId, Fault fault) {
        if (fault == Fault.SENSOR_STUCK_OPEN) {
            return false;
        }
        return doorClosedBySlot.getOrDefault(key(cabinetId, slotId), Boolean.TRUE);
    }

    private Presence sensorPresence(Long cabinetId, Long slotId, Fault fault) {
        return switch (fault) {
            case SENSOR_NO_ITEM -> Presence.UNKNOWN;
            case ITEM_UNDETECTED -> Presence.ABSENT;
            default -> Boolean.TRUE.equals(itemInsideBySlot.get(key(cabinetId, slotId)))
                    ? Presence.PRESENT : Presence.ABSENT;
        };
    }

    @Override
    public boolean available(Long cabinetId) {
        return effectiveFault(cabinetId) != Fault.OFFLINE;
    }

    @Override
    public SlotSensor probe(Long cabinetId, Long slotId) {
        Fault fault = effectiveFault(cabinetId);
        return new SlotSensor(sensorDoorClosed(cabinetId, slotId, fault),
                sensorPresence(cabinetId, slotId, fault), java.time.LocalDateTime.now());
    }

    public void simulateItemInside(Long cabinetId, Long slotId, boolean inside) {
        itemInsideBySlot.put(key(cabinetId, slotId), inside);
    }

    public void simulateDoor(Long cabinetId, Long slotId, boolean closed) {
        doorClosedBySlot.put(key(cabinetId, slotId), closed);
    }

    private static String key(Long cabinetId, Long slotId) {
        return cabinetId + ":" + slotId;
    }

    private Fault effectiveFault(Long cabinetId) {
        Fault targeted = faultByCabinet.get(cabinetId);
        return targeted != null ? targeted : defaultFault;
    }

    private long nextSeq(Long cabinetId, Long slotId) {
        return seqBySlot.computeIfAbsent(cabinetId + ":" + slotId, k -> new AtomicLong()).incrementAndGet();
    }

    public void setDefaultFault(Fault fault) {
        log.warn("柜机模拟器故障模式设为 {}（仅 dev/test 生效，不影响生产）", fault);
        this.defaultFault = fault == null ? Fault.NORMAL : fault;
        this.faultByCabinet.clear();
    }

    public void setFaultFor(Long cabinetId, Fault fault) {
        if (fault == null) {
            faultByCabinet.remove(cabinetId);
            return;
        }
        log.warn("柜机模拟器对 cabinetId={} 注入故障 {}", cabinetId, fault);
        faultByCabinet.put(cabinetId, fault);
    }

    public void reset() {
        this.defaultFault = Fault.NORMAL;
        this.faultByCabinet.clear();
        this.seqBySlot.clear();
        this.receiptByRequestId.clear();
        this.doorClosedBySlot.clear();
        this.itemInsideBySlot.clear();
    }

    public Fault currentFault(Long cabinetId) {
        return effectiveFault(cabinetId);
    }
}
