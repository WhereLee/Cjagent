package com.wherelee.cabinet.infrastructure.device;

import com.wherelee.cabinet.application.device.DeviceChannel;
import com.wherelee.cabinet.domain.enums.ReportEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 柜机模拟器：<b>它的唯一职责是可控地失败</b>。
 *
 * <p>刻意<b>不</b>还原物理设备（不做门磁抖动、不做电量、不做温度）。本项目的目的是让业务侧
 * 在"网络波动、设备损坏、谎报、乱序"下仍然正确，所以模拟器要提供的是<b>可枚举、可指定、可复现</b>
 * 的故障，而不是一套没人能验证的仿真。
 *
 * <p>三条约束是这套设计的边界：
 * <ul>
 *   <li>只注册在 dev/test/integration（{@code @Profile}）：<b>prod 用到它等于业务在跟自己玩</b>，
 *       启动期直接没有这个 bean，装配就失败；</li>
 *   <li>故障可以<b>按柜机定向</b>：否则一次注入会把同库其它柜机的用例一起打挂，
 *     测试之间互相污染，最后只能靠运气跑绿；</li>
 *   <li>{@link #NO_RECEIPT} 返回<b>永不完成</b>的 future（而不是抛异常）：真实设备的"没回执"就是这样，
 *       超时判定必须由调用方做。</li>
 * </ul>
 */
@Component
@Profile({"dev", "test", "integration"})
public class SimulatedCabinetChannel implements DeviceChannel {

    private static final Logger log = LoggerFactory.getLogger(SimulatedCabinetChannel.class);

    /** 可控故障集，逐个对应规划里的故障矩阵。 */
    public enum Fault {
        /** 正常执行 */
        NORMAL,
        /** 不回执（超时） */
        NO_RECEIPT,
        /** 谎报关门：success=true 但传感器没确认 */
        LYING_CLOSED,
        /** 错报目标：操作了另一个格口（部分成功/串位） */
        WRONG_TARGET,
        /** 回了一个比已记录更旧的设备序号（乱序重放） */
        STALE_SEQ,
        /** 设备明确执行失败 */
        EXEC_FAIL,
        /** 柜机离线 */
        OFFLINE
    }

    /** 错报目标时用的偏移：只要与真实 slotId 不同即可，不必有意义。 */
    private static final long WRONG_TARGET_OFFSET = 900001L;

    /** STALE_SEQ 用的固定小序号：一定小于第一次正常上报写入的 seq，才能验出"旧序号不推进状态"。 */
    private static final long STALE_SEQ_VALUE = 0L;

    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "cabinet-simulator");
        thread.setDaemon(true);
        return thread;
    });

    /** 每个 (cabinet:slot) 的单调序号，模拟设备侧 seq。 */
    private final Map<String, AtomicLong> seqBySlot = new ConcurrentHashMap<>();

    /** 默认故障模式（volatile：测试线程改，模拟线程读）。 */
    private volatile Fault defaultFault = Fault.NORMAL;

    /** 按柜机定向的故障，优先级高于默认。 */
    private final Map<Long, Fault> faultByCabinet = new ConcurrentHashMap<>();

    @Value("${cabinet.simulator.reply-delay-ms:60}")
    private volatile long replyDelayMs;

    @Override
    public CompletableFuture<CommandReceipt> send(CommandTicket ticket) {
        Fault fault = effectiveFault(ticket.cabinetId());
        if (fault == Fault.OFFLINE) {
            // 离线时通道应当"发不出去"，但业务侧已先查过 available()；真到这里的场景是"离线瞬间竞态"，
            // 用永不完成模拟，让超时兜底
            return new CompletableFuture<>();
        }
        if (fault == Fault.NO_RECEIPT) {
            return new CompletableFuture<>();
        }

        long seq = nextSeq(ticket.cabinetId(), ticket.slotId());
        CompletableFuture<CommandReceipt> future = new CompletableFuture<>();
        Runnable reply = () -> future.complete(receiptFor(fault, ticket, seq));
        if (replyDelayMs <= 0) {
            reply.run();
        } else {
            executor.schedule(reply, replyDelayMs, TimeUnit.MILLISECONDS);
        }
        return future;
    }

    private CommandReceipt receiptFor(Fault fault, CommandTicket ticket, long seq) {
        return switch (fault) {
            case LYING_CLOSED -> new CommandReceipt(ticket.requestId(), true, ReportEvent.DOOR_CLOSED,
                    seq, false, ticket.slotId(), "设备称已关门，门磁未确认");
            case WRONG_TARGET -> new CommandReceipt(ticket.requestId(), true, ReportEvent.DOOR_OPENED,
                    seq, true, ticket.slotId() + WRONG_TARGET_OFFSET, "回执格口与指令不符");
            case STALE_SEQ -> new CommandReceipt(ticket.requestId(), true, ReportEvent.DOOR_OPENED,
                    STALE_SEQ_VALUE, true, ticket.slotId(), "序号早于已记录（乱序重放）");
            case EXEC_FAIL -> new CommandReceipt(ticket.requestId(), false, ReportEvent.FAULT,
                    seq, true, ticket.slotId(), "电机阻塞，开柜失败");
            case NORMAL, NO_RECEIPT, OFFLINE -> new CommandReceipt(ticket.requestId(), true,
                    ticket.action().isRetrieveLike() || ticket.action() == com.wherelee.cabinet.domain.enums.CommandAction.OPEN
                            ? ReportEvent.DOOR_OPENED : ReportEvent.DOOR_CLOSED,
                    seq, true, ticket.slotId(), null);
        };
    }

    @Override
    public boolean available(Long cabinetId) {
        return effectiveFault(cabinetId) != Fault.OFFLINE;
    }

    private Fault effectiveFault(Long cabinetId) {
        Fault targeted = faultByCabinet.get(cabinetId);
        return targeted != null ? targeted : defaultFault;
    }

    private long nextSeq(Long cabinetId, Long slotId) {
        return seqBySlot.computeIfAbsent(cabinetId + ":" + slotId, k -> new AtomicLong()).incrementAndGet();
    }

    /** 测试与本地调试用：设置全局故障模式。 */
    public void setDefaultFault(Fault fault) {
        log.warn("柜机模拟器故障模式设为 {}（仅 dev/test 生效，不影响生产）", fault);
        this.defaultFault = fault == null ? Fault.NORMAL : fault;
        this.faultByCabinet.clear();
    }

    /** 测试与本地调试用：只对某台柜机注入故障，其余柜机保持正常。 */
    public void setFaultFor(Long cabinetId, Fault fault) {
        if (fault == null) {
            faultByCabinet.remove(cabinetId);
            return;
        }
        log.warn("柜机模拟器对 cabinetId={} 注入故障 {}", cabinetId, fault);
        faultByCabinet.put(cabinetId, fault);
    }

    /** 全部还原成正常，测试之间必须显式隔离。 */
    public void reset() {
        this.defaultFault = Fault.NORMAL;
        this.faultByCabinet.clear();
        this.seqBySlot.clear();
    }

    public Fault currentFault(Long cabinetId) {
        return effectiveFault(cabinetId);
    }
}
