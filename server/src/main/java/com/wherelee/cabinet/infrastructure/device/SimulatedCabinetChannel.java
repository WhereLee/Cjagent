package com.wherelee.cabinet.infrastructure.device;

import com.wherelee.cabinet.application.device.DeviceChannel;
import com.wherelee.cabinet.domain.enums.CommandAction;
import com.wherelee.cabinet.domain.enums.Presence;
import com.wherelee.cabinet.domain.enums.ReportEvent;
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
 *   <li><b>设备端幂等</b>：同一 requestId 的重复下发返回首次结果，不会把门再开一次。
 *       服务端的重试（第 13 刀回扫）就建立在这个约定上；模拟器不实现它，就等于
 *       “重试安全”是个只存在于文档里的假设。</li>
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
        OFFLINE,
        // 下面三类不还原物理，只让传感器“回答得不一样”——结束判据（§3）全靠这三种回答

        /** 门磁卡住：无论实不实际关上，永远回答“开着”。
         * 业务必须保守：不可售、计费不停，最终只能靠运维而不是靠猜。 */
        SENSOR_STUCK_OPEN,
        /** 这台柜机没有柜内物检（或传感器脏）：回答 UNKNOWN，而不是“没东西”。
         * 真实柜机大多只有门磁，这一类是常态不是意外。 */
        SENSOR_NO_ITEM,
        /** 漏检：里面有东西却回答“没有”。小件、透明物、吸光物的现实盲区，
         * 用它证明“卖错格子”的底线不靠传感器守住（不变量 I12 的入题）。 */
        ITEM_UNDETECTED
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

    /**
     * 模拟器的物理记忆：门关了没（缺省 true，新格口与清柜后都是关着的）。
     *
     * <p>它存在的唯一理由：让“回执里说了什么”与“传感器现在是什么”可以不相等。
     * 没这个区分，谎报关门就测不出来。
     */
    private final Map<String, Boolean> doorClosedBySlot = new ConcurrentHashMap<>();

    /** 模拟器的柜内记忆：里面有东西没（缺省 false，即顺利路径为“无物”）。 */
    private final Map<String, Boolean> itemInsideBySlot = new ConcurrentHashMap<>();

    /** 设备端幂等表的上限：真实柜机内存有限，这里同理，超量按插入序丢最旧。 */
    private static final int IDEMPOTENCY_CACHE = 512;

    /** requestId → 首次回执（重复下发不再执行物理动作，只回首次结果）。 */
    private final Map<String, CommandReceipt> receiptByRequestId = Collections.synchronizedMap(
            new LinkedHashMap<>(64, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CommandReceipt> eldest) {
                    return size() > IDEMPOTENCY_CACHE;
                }
            });

    /** 默认故障模式（volatile：测试线程改，模拟线程读）。 */
    private volatile Fault defaultFault = Fault.NORMAL;

    /** 按柜机定向的故障，优先级高于默认。 */
    private final Map<Long, Fault> faultByCabinet = new ConcurrentHashMap<>();

    @Value("${cabinet.simulator.reply-delay-ms:60}")
    private volatile long replyDelayMs;

    @Override
    public CompletableFuture<CommandReceipt> send(CommandTicket ticket) {
        CommandReceipt remembered = receiptByRequestId.get(ticket.requestId());
        if (remembered != null) {
            // 重试/回扫再发一次：设备不重做物理动作，只把首次结果重新送回去
            log.info("模拟器命中设备端幂等表，返回首次结果 requestId={} success={}",
                    ticket.requestId(), remembered.success());
            return CompletableFuture.completedFuture(remembered);
        }
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
        Runnable reply = () -> {
            // 顺序不能反：**先改物理，再算回执**。我先把这两步换了（觉得“回执决定物理”更自然），
            // 结果 normalOpenThenCloseVerify 全红：算 sensorOk 时读到的还是“开柜那一步留下的开着”，
            // 于是每一次正常关门都被报成谎报。这个错很典型：两个状态谁先谁后不是风格问题。
            applyPhysics(ticket, fault);
            CommandReceipt receipt = receiptFor(fault, ticket, seq);
            // 只在真拿到回执时入表：没回执（超时）不能被记成“首次结果”，
            // 否则一次网络抖动会把这条指令永远钉在“没做过”上
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
        return switch (fault) {
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
                // “门关了”这个结论由传感器答，而不是由“这条指令是关门”定：
                // 门磁卡住时它就不认，业务必须不能拿“我发了关门命令”当成“门已关”
                boolean sensorOk = ticket.action() != CommandAction.CLOSE_VERIFY
                        || sensorDoorClosed(ticket.cabinetId(), ticket.slotId(), fault);
                yield new CommandReceipt(ticket.requestId(), true,
                        opening ? ReportEvent.DOOR_OPENED : ReportEvent.DOOR_CLOSED,
                        seq, sensorOk, ticket.slotId(),
                        sensorOk ? null : "门磁未确认关闭（传感器卡住，或门确实没关）");
            }
        };
    }

    /**
     * 物理变化按“这条指令在现实中真做了什么”算：
     * <ul>
     *   <li>{@code OFFLINE / NO_RECEIPT / EXEC_FAIL} —— 根本没执行，不动；</li>
     *   <li>{@code WRONG_TARGET} —— 真开的是<b>另一个</b>格子，所以只能改那个；</li>
     *   <li>{@code LYING_CLOSED} —— 它“说”关上了而物理上没关：关门那一步不动，
     *       开柜那一步照旧要开（谎报只谎在“关上”这一件事上）；</li>
     *   <li>其余注入（序号旧、物检缺失、物检漏检、门磁卡住）——只影响<b>传感器答案</b>，
     *       不影响门与件的实际状态。</li>
     * </ul>
     *
     * <p>这里吃过两次红：先把物理改成“只有 NORMAL 才动”，结果序号注入顺带把门变成了没关；
     * 再把 SENSOR_NO_ITEM 落到 default 之外，又让“没有物检”把门态也一并报坏了。
     * <b>一个故障只该影响它那一维</b>，否则测出来的结果描述的不是那个故障。
     */
    private void applyPhysics(CommandTicket ticket, Fault fault) {
        switch (fault) {
            case OFFLINE, NO_RECEIPT, EXEC_FAIL -> {
                // 没执行，就没有物理变化
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

    /** 开柜类动作把门置开，关门校验把门置关。 */
    private void setDoor(Long cabinetId, Long slotId, CommandAction action) {
        boolean opening = action == CommandAction.OPEN || action == CommandAction.OPEN_TEMP;
        doorClosedBySlot.put(key(cabinetId, slotId), !opening);
    }

    /** 门磁结论：注入优先于物理（卡住时物理已无意义）。 */
    private boolean sensorDoorClosed(Long cabinetId, Long slotId, Fault fault) {
        if (fault == Fault.SENSOR_STUCK_OPEN) {
            return false;
        }
        return doorClosedBySlot.getOrDefault(key(cabinetId, slotId), Boolean.TRUE);
    }

    /**
     * 柜内物检结论。注意缺省是“无物”而不是“不知道”：
     * 模拟器默认走顺利路径，“没装物检”与“漏检”都由上面两个注入专门表达。
     */
    private Presence sensorPresence(Long cabinetId, Long slotId, Fault fault) {
        return switch (fault) {
            case SENSOR_NO_ITEM -> Presence.UNKNOWN;
            case ITEM_UNDETECTED -> Presence.ABSENT;   // 里面有东西也报没有
            default -> Boolean.TRUE.equals(itemInsideBySlot.get(key(cabinetId, slotId)))
                    ? Presence.PRESENT : Presence.ABSENT;
        };
    }

    @Override
    public boolean available(Long cabinetId) {
        return effectiveFault(cabinetId) != Fault.OFFLINE;
    }

    /**
     * 读一次传感器现状。<b>只读不写</b>：业务不能靠这个调用替用户把门关上，
     * 也不能靠它把“没东西”写成一个事实。
     */
    @Override
    public SlotSensor probe(Long cabinetId, Long slotId) {
        Fault fault = effectiveFault(cabinetId);
        return new SlotSensor(sensorDoorClosed(cabinetId, slotId, fault),
                sensorPresence(cabinetId, slotId, fault), java.time.LocalDateTime.now());
    }

    /**
     * 测试钩子：把某格口“柜内有物”当真了。唯一用途是让测试能表达
     * “用户结单了、东西还在里面”这个必须被拦下来的现场。
     */
    public void simulateItemInside(Long cabinetId, Long slotId, boolean inside) {
        itemInsideBySlot.put(key(cabinetId, slotId), inside);
    }

    /**
     * 测试钩子：强制物理门态（true=关）。用来制造“命令与传感器不相等”的组合，
     * 比如“他推上门又拉开走了”。
     */
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
        this.receiptByRequestId.clear();
        // 物理与柜内记忆也必须清：上一个用例留下的“门开着/有东西”会把下一个用例
        // 卡在结束判据上，症状是“莫名结不了单”，而它跟并发、与业务代码都无关
        this.doorClosedBySlot.clear();
        this.itemInsideBySlot.clear();
    }

    public Fault currentFault(Long cabinetId) {
        return effectiveFault(cabinetId);
    }
}
