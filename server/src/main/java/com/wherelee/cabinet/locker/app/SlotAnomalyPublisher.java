package com.wherelee.cabinet.locker.app;

import com.wherelee.cabinet.notify.api.NoticeChannel;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.device.api.EvidenceMoment;
import com.wherelee.cabinet.device.api.EvidencePort;
import com.wherelee.cabinet.device.api.EvidenceRecord;
import com.wherelee.cabinet.device.api.FaultPort;
import com.wherelee.cabinet.device.api.FaultType;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.OrderStatus;
import com.wherelee.cabinet.locker.api.CompartmentAnomaly;
import com.wherelee.cabinet.locker.api.OutboxPort;
import com.wherelee.cabinet.locker.api.Presence;
import com.wherelee.cabinet.locker.domain.BizCompartment;
import com.wherelee.cabinet.locker.infra.BizCompartmentMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import com.wherelee.cabinet.servicedesk.api.WorkOrderPort;
import com.wherelee.cabinet.servicedesk.api.WorkOrderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 异常事件的唯一出口：标异常、开单、留证据、写台账、催办阶梯。
 *
 * <p>为什么从门态状态机里拆出来：门态讲的是“门开/门关/有没有东西”这几件物理事实，
 * 而“标了异常要开单、要通知、要按阶梯催人、要写台账”是<b>对外发生的事</b>。
 * 两者混在一个类里时，状态机的每次改动都被迫在一个 560 行的文件里读完整条运营链路。
 *
 * <p>方向是单向的：状态机调用本类；本类不反向调状态机（解除异常由状态机自己判）。
 */
@Service
public class SlotAnomalyPublisher {

    private static final Logger log = LoggerFactory.getLogger(SlotAnomalyPublisher.class);

    private final BizCompartmentMapper slotMapper;
    private final FaultPort faults;
    private final WorkOrderPort workOrders;
    private final EvidencePort evidence;
    /**
     * 待投递的事实（批 4）：开工单与发通知都不再同步调，改为入队由中继投递。
     * 这两条链的入口多在业务事务里，同步调时下游失败会把上游事实一起回滚。
     */
    private final OutboxPort outbox;
    /** 遗留物归因：发现遗留物时活动单早已结束，能指认的就是这个格口最近一张终态单的主人。 */
    private final BizStorageOrderMapper orderMapper;

    // 19D 阶梯的三个数（用户在 2026-10-11 给出）：3 分钟首响、5 分钟连发三条、10 分钟必须处理
    @Value("${cabinet.door-watch.first-remind-minutes:3}")
    private long firstRemindMinutes;
    @Value("${cabinet.door-watch.repeat-remind-minutes:5}")
    private long repeatRemindMinutes;
    @Value("${cabinet.door-watch.escalate-minutes:10}")
    private long escalateMinutes;
    /** “连发三条”的条数（用户描述的“连发提醒”，不是发一条长消息）。 */
    @Value("${cabinet.door-watch.repeat-times:3}")
    private int repeatRemindTimes;

    public SlotAnomalyPublisher(BizCompartmentMapper slotMapper, FaultPort faults,
                                WorkOrderPort workOrders, EvidencePort evidence, OutboxPort outbox,
                                BizStorageOrderMapper orderMapper) {
        this.slotMapper = slotMapper;
        this.faults = faults;
        this.workOrders = workOrders;
        this.evidence = evidence;
        this.outbox = outbox;
        this.orderMapper = orderMapper;
    }

    /**
     * 落一条证据记录（19C）。<b>不写 photoRef</b>：我们是虚拟设备，没有摄像头，
     * 宁可留空也不能存一个不存在的图片引用——那会比没记录更坏（业务员会去调一张不存在的图）。
     */
    public void recordEvidence(EvidenceMoment moment, BizCompartment slot, String note) {
        // 三值结论直接带上：测不准就是 UNKNOWN，不折成“没东西”
        Presence presence = slot.getPresence() == null ? Presence.UNKNOWN : slot.getPresence();
        evidence.record(new EvidenceRecord(slot.getTenantId(), slot.getCabinetId(), slot.getId(),
                moment.name(), "DEVICE_PROBE", presence.name(), note));
    }

    /**
     * 格口转入“遗留物”异常：关门复检有物 / 代关检测有物 / 下一位上报，三个入口共用。
     *
     * <p>标异常与开单由 {@link #markAnomaly} 负责；这里额外补一步
     * <b>通知物主</b>（第 21 刀，用户定：自动发站内消息、业务员可选打电话）——
     * 发现遗留物的那一刻，最该知道的人就是把它落在这里的那位。
     */
    public void markContentLeft(Long slotId, String reason) {
        markAnomalyAndLog(slotId, CompartmentAnomaly.CONTENT_LEFT, reason);
        notifyOwnerForLeftover(slotId);
    }

    /**
     * 门关了却测不到柜内：标“待确认清空”，不可分配，等业务运维或下一位使用者确认。
     *
     * <p>不会拚掉更强的锁：“遗留物”是已经看见有东西，“测不到”只是没凭据。
     * 让弱锁盖掉强锁，就会出现“上报过别人东西的格口因为一次探测失败被降级成待确认”然后被人清走。
     */
    public void markContentUnverified(Long slotId, String reason) {
        BizCompartment slot = slotMapper.selectById(slotId);
        if (slot != null && slot.getAnomaly() != null && slot.getAnomaly() != CompartmentAnomaly.DOOR_OPEN) {
            log.debug("已有更强的异常，不降级为待确认 slotNo={} anomaly={}", slot.getSlotNo(), slot.getAnomaly());
            return;
        }
        markAnomalyAndLog(slotId, CompartmentAnomaly.CONTENT_UNVERIFIED, reason);
    }

    /**
     * 传感器自相矛盾：设备称已关门、门磁却不认（谎报），或门磁与物检互斥。
     *
     * <p>这类<b>不能自动归类</b>：系统没有足够信息判断到底是门没关、还是传感器坏了，
     * 猜哪一边都可能造成不可逆的后果（把还有东西的格子卖出去，或把一个空柜永久锁死）。
     */
    public void markSensorConflict(Long slotId, String reason) {
        markAnomalyAndLog(slotId, CompartmentAnomaly.SENSOR_CONFLICT, reason);
    }

    /** 容错期满而门还开着：这时才真的是异常（计费从开柜就在跑，必须有人看见）。
     *
     * <p>19D 把提醒阶梯也落在这里（而不是各调度分支里）：这里已经是“标异常”的唯一出口，
     * 开单也在同一个调用链上。阶梯用**已催次数**而不是“当前几分”判定，因为巡检可能被
     * 租约接管后重跑，拿墙上时间判会在同一分钟重复发提醒。
     */
    public void markDoorOpenPastTolerance(Long slotId, long openMinutes) {
        markAnomalyAndLog(slotId, CompartmentAnomaly.DOOR_OPEN,
                "柜门已开 " + openMinutes + " 分钟仍未关闭，计费已起计，直到关门或用户结束订单");
        runReminderLadder(slotId, openMinutes);
    }

    /** 人工清柜完成：把这次解除落成 RECOVER 事件（状态列只存当前值，回答不了“谁什么时候清的”）。 */
    public void recordManualRecovery(Long slotId, String note) {
        BizCompartment slot = slotMapper.selectById(slotId);
        if (slot == null) {
            return;
        }
        recordFault(slotId, FaultType.Action.RECOVER, "人工清柜解除异常：" + note);
    }

    /**
     * 异常写入的统一入口：同一条异常同时落“格口当前态”与“事件流水”两处。
     *
     * <p>为什么两处都要：格口上的字段只存“现在是什么”，回答不了“这段时间里响过几次”；
     * 而只有流水没有当前态，分配判定就只能每次去扫事件表。业务运维拿的是流水（台账），
     * 系统拿的是当前态（能不能卖）。
     *
     * <p>{@code fault_type} 故意不新建枚举值而用 reason 文字区分：那列上有 CHECK 约束，
     * 加一个值就要改一次迁移，而“到底是哪一类”的权威已经在 anomaly 列上了。
     * 拿一个字段当真值、另一个当报信，比两处各自建一套枚举更不容易漂移。
     */
    public void markAnomaly(Long slotId, CompartmentAnomaly anomaly, String reason) {
        markAnomalyAndLog(slotId, anomaly, reason);
    }

    /** 代关后的流水（状态机只决定“该记一笔”，怎么记账由本类落）。 */
    public void noteHelperClosed(Long slotId, String note) {
        workOrders.noteStep(slotId, WorkOrderType.DOOR_NOT_CLOSED, "HELPER_CLOSED", note);
    }

    /** 门关了、事实自己消失：把“门未关”工单转 AUTO_CLOSED 留底。 */
    public void autoCloseDoorClosed(Long slotId) {
        workOrders.autoClose(slotId, WorkOrderType.DOOR_NOT_CLOSED);
    }

    /** 留一条设备故障台账（RECOVER / ALARM / MARK_UNAVAILABLE）。 */
    public void recordFault(Long slotId, FaultType.Action action, String reason) {
        BizCompartment slot = slotMapper.selectById(slotId);
        if (slot == null) {
            return;
        }
        faults.record(slot.getCabinetId(), slot.getId(), action, 0, reason);
    }

    private void markAnomalyAndLog(Long slotId, CompartmentAnomaly anomaly, String reason) {
        LocalDateTime now = LocalDateTime.now();
        slotMapper.markAnomaly(slotId, anomaly, now, reason);
        BizCompartment slot = slotMapper.selectById(slotId);
        if (slot == null) {
            log.warn("标异常时格口已不存在 slotId={} anomaly={}", slotId, anomaly);
            return;
        }
        faults.record(slot.getCabinetId(), slot.getId(),
                anomaly.autoRecoverable() ? FaultType.Action.ALARM : FaultType.Action.MARK_UNAVAILABLE, 0, reason);
        // 19B：异常一旦落下就同时开一张工单（开单幂等，重复标同一种异常不会刷出多张）。
        // 走 outbox 而不是直接调：这条链的入口多在业务事务里（上报遗留物、取件、取消、握手收敛），
        // 同步调时下游一抛异常会把共享的物理事务标成 rollback-only——加 try/catch 也挡不住，
        // 于是“格口有异常”这个事实连同用户的操作一起消失。现在只写一行本地表，投递交给中继。
        outbox.appendWorkOrderAnomaly(slotId, anomaly, reason, slotId + ":" + anomaly + ":" + now);
    }

    /**
     * 提醒阶梯：3 分钟一次、5 分钟连发三条并做检测、10 分钟升级到“业务员必须处理”。
     *
     * <p>为什么能靠“已催几次”推台阶而不存台阶字段：每一步都写一行事件流水，
     * 次数就是状态。多一列就要多一处同步，而那处同步一定会错。
     */
    private void runReminderLadder(Long slotId, long openMinutes) {
        BizCompartment slot = slotMapper.selectById(slotId);
        if (slot == null || slot.getAnomaly() != CompartmentAnomaly.DOOR_OPEN) {
            return;
        }
        WorkOrderType type = WorkOrderType.DOOR_NOT_CLOSED;
        int already = workOrders.remindedTimes(slotId, type);
        // 看管期间每次检测都留一条（“5 分钟检测到有物时再来一张”就落在这里）
        recordEvidence(EvidenceMoment.WATCH_DETECT, slot,
                "门未关第 " + already + " 次检测：柜内=" + slot.getPresence());
        int totalReminds = 1 + repeatRemindTimes;
        if (already >= totalReminds) {
            // 提醒档已走完：该做的只剩升级。到点没升级成功就下一轮再试，不卡死也不刷屏
            if (openMinutes >= escalateMinutes) {
                workOrders.escalate(slotId, type, "门未关满 " + escalateMinutes
                        + " 分钟，需线上业务员处理并联系客户");
            }
            return;
        }
        if (already == 0) {
            workOrders.remind(slotId, type, "提醒 1/" + totalReminds + "：门开了 "
                    + firstRemindMinutes + " 分钟未关（已开单，计费从开柜起算）");
            return;
        }
        // 第二档起：连发提醒（到 repeat-remind-minutes 才开始，不是“下一轮巡检就发”）
        if (openMinutes < repeatRemindMinutes) {
            return;
        }
        workOrders.remind(slotId, type, "提醒 " + (already + 1) + "/" + totalReminds
                + "：门已开 " + openMinutes + " 分钟，请关门或带走物品后结束");
        // 每发一次就做一次检测：检测说柜内有物，这张单就是紧急异常（现场可能正在丢件）
        if (slot.getPresence() == Presence.PRESENT) {
            workOrders.escalate(slotId, type, "检测到柜内有物而门还开着：升为紧急异常");
        }
    }

    /**
     * 遗留物通知物主：找这个格口最近一张终态单的主人，打标并发一条站内消息。
     *
     * <p>为什么是“最近一张终态单”：发现遗留物时那张活动单早已结束
     * （取物关门后才判遗留，或更晚由下一位上报），格口上没有活动单可指认。
     * 找不到终态单（无主物）就不发，工单自己会走人工。
     *
     * <p>两处幂等：打标条件 {@code leftover_reported_at is null}（不刷时间），
     * 消息 dedup_key 挂工单 ID（同一张未关闭工单只发一条，重复观察不打扰物主）。
     * 通知失败不能拖挂主链路：异常与工单是事实，消息只是触达，下一轮重复观察会再试。
     */
    private void notifyOwnerForLeftover(Long slotId) {
        try {
            BizCompartment slot = slotMapper.selectById(slotId);
            if (slot == null) {
                return;
            }
            BizStorageOrder previous = orderMapper.selectOne(Wrappers.<BizStorageOrder>lambdaQuery()
                    .eq(BizStorageOrder::getSlotId, slotId)
                    .in(BizStorageOrder::getStatus, OrderStatus.CLOSED, OrderStatus.CANCELLED)
                    .orderByDesc(BizStorageOrder::getFinishedAt)
                    .last("limit 1"));
            if (previous == null) {
                log.info("遗留物无法归因到终态单，仅开工单不发消息 slotId={}", slotId);
                return;
            }
            orderMapper.markLeftoverReported(previous.getId(), LocalDateTime.now());
            // 工单本身也是待投递的事实，此刻可能还没开出来——所以既不能用“工单 ID 存在”
            // 当发消息的前提，也不能拿它当去重键：那样第二次观察时工单还没投递，
            // 消息就永远发不出去（批 4 把它拆成 outbox 之后暴露的时序依赖）。
            // 去重键改用订单号：同一张终态单只提醒一次，语义不变且不依赖异步结果。
            Long workOrderId = workOrders.openIdOf(slotId, WorkOrderType.ITEM_LEFT);
            // 发消息同样走 outbox：本方法的入口是“关门复检 / 代关 / 下一位上报”，
            // 多在业务事务里；通知失败不能把物主的那张单一起带走。
            outbox.appendOwnerNotice(NoticeChannel.SYSTEM_MESSAGE, "LEFTOVER_OWNER",
                    previous.getOrderNo() + ":OWNER_MSG",
                    "你的暂存柜格口 " + slot.getSlotNo() + " 检测到遗留物品，请到柜取回。"
                            + "取回路径：小程序-我的寄存单-该订单-取回物品",
                    previous.getCustomerId(), workOrderId, previous.getOrderNo());
        } catch (RuntimeException e) {
            // 开单已成功，通知失败只记不抛：主链路（异常+工单）已闭环，触达下一轮再试
            log.error("遗留物通知物主失败（不阻断主链路）slotId={}", slotId, e);
        }
    }
}
