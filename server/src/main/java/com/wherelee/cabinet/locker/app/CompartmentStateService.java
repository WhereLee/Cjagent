package com.wherelee.cabinet.locker.app;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.device.api.DeviceChannel;
import com.wherelee.cabinet.notify.api.NoticePort;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.locker.domain.BizCompartment;
import com.wherelee.cabinet.device.domain.BizItemEvidence;
import com.wherelee.cabinet.device.api.EvidenceSource;
import com.wherelee.cabinet.device.domain.BizFaultEvent;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.locker.api.CompartmentAnomaly;
import com.wherelee.cabinet.device.api.FaultType;
import com.wherelee.cabinet.domain.enums.OrderStatus;
import com.wherelee.cabinet.locker.api.Presence;
import com.wherelee.cabinet.domain.enums.WorkOrderType;
import com.wherelee.cabinet.locker.infra.BizCompartmentMapper;
import com.wherelee.cabinet.device.infra.BizFaultEventMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 格口的门态、物检与异常的唯一写入点。
 *
 * <p>为什么要集中：这四列（门关没关、谁开关的、里面有没有东西、异常是哪一类）互相有约束，
 * 散着写一定会出现"记了关门却没清异常"、"标了异常却没记原因"这类只有一半的现场。
 * 集中一处之后，"影响行数为 0 意味着什么"只需要在这里回答一次。
 *
 * <p><b>本类只读设备、不命令设备</b>：门是用户用手关的，业务侧能问的只有门磁与物检。
 * 如果这里出现"用命令把门关上的"接口，就等于平台替用户把门关了——谎报再也测不出来
 * （docs/门态与物品争议设计.md §9）。
 */
@Service
public class CompartmentStateService {

    private static final Logger log = LoggerFactory.getLogger(CompartmentStateService.class);

    private final BizCompartmentMapper slotMapper;
    private final BizFaultEventMapper faultMapper;
    private final DeviceChannel channel;
    /**
     * 开单入口（19B）。挂在“标异常的唯一出口”上而不是各业务分支里：
     * 分散调用一定漏路径，而漏的往往是异常那一条。
     */
    private final com.wherelee.cabinet.application.workorder.WorkOrderIntake intake;
    /** 工单流转（19B）：门关上时把“门未关”工单自动转化留底。 */
    private final com.wherelee.cabinet.application.workorder.WorkOrderService workOrders;
    /**
     * 证据留底（19C）：四个采集点各一条。
     *
     * <p>写在这里而不是各调用方自己写：这已经是门态与物检的唯一写入点，
     * 分散写必然出现“实时路径记了、巡检路径漏了”那种同事件不同结果（第 13 刀为此合并过一次）。
     */
    private final com.wherelee.cabinet.device.infra.BizItemEvidenceMapper evidenceMapper;
    /** 遗留物通知（第 21 刀）：物主收到的站内消息走它，一张未关闭工单只发一条。 */
    private final NoticePort notices;
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

    /**
     * 物检结论的可用时效（分钟）。
     *
     * <p>为什么必须有：设备"看了一眼"的时刻不等于"现在"。没有这个上限，
     * 一次三天前的"空柜"读数可以一直替这个格口作证，直到有人把它卖给下一位用户。
     */
    @Value("${cabinet.sensor.presence-max-age-minutes:2}")
    private int presenceMaxAgeMinutes;

    public CompartmentStateService(BizCompartmentMapper slotMapper, BizFaultEventMapper faultMapper,
                                   DeviceChannel channel,
                                   com.wherelee.cabinet.application.workorder.WorkOrderIntake intake,
                                   com.wherelee.cabinet.application.workorder.WorkOrderService workOrders,
                                   com.wherelee.cabinet.device.infra.BizItemEvidenceMapper evidenceMapper,
                                   NoticePort notices,
                                   BizStorageOrderMapper orderMapper) {
        this.slotMapper = slotMapper;
        this.faultMapper = faultMapper;
        this.channel = channel;
        this.intake = intake;
        this.workOrders = workOrders;
        this.evidenceMapper = evidenceMapper;
        this.notices = notices;
        this.orderMapper = orderMapper;
    }

    /**
     * 一次读数的结果（物检结论已经落库）。
     *
     * <p>它不自己判新不新鲜：时效是可配的业规则，属于本服务（{@link #fresh(Sensing)}），
     * 把判定放进数据记录里就会出现“每个记录对象自己拿一份配置”的无法测试形态。
     */
    public record Sensing(boolean doorClosed, Presence presence, LocalDateTime sensedAt) {
    }

    /**
     * 问一次设备传感器，并把物检结论连同时刻落库。
     *
     * <p>落库而不是只用返回值：业务运维与纠纷还原要能看到"当时设备报了什么、什么时候报的"。
     */
    public Sensing sense(Long cabinetId, Long slotId) {
        DeviceChannel.SlotSensor sensor = channel.probe(cabinetId, slotId);
        LocalDateTime at = sensor.sensedAt() == null ? LocalDateTime.now() : sensor.sensedAt();
        if (sensor.presence() != null) {
            slotMapper.recordPresence(slotId, sensor.presence(), at);
        }
        return new Sensing(sensor.doorClosed(), sensor.presence(), at);
    }

    /** 物检结论能不能当凭据（时效在 {@code presence-max-age-minutes} 内）。 */
    public boolean fresh(Sensing sensing) {
        return sensing.sensedAt() != null
                && java.time.Duration.between(sensing.sensedAt(), LocalDateTime.now()).toMinutes()
                <= presenceMaxAgeMinutes;
    }

    /**
     * 记“门开了”：只记下起点，<b>不标异常、不写故障流水</b>。
     *
     * <p>为什么：容错期内门开着是正常态（用户正在放件），每次正常开柜都写一条“门未关”故障
     * 等于把台账弄成噪声，运维很快就再也不看它了。真正的异常是在容错期满、门还开着时说的
     * （由 {@code StorageOrderService.toleranceExpired} 那边标）。
     *
     * <p>{@code markDoorOpen} 影响 0 行是<b>正常分支</b>（重复回执、重试、临时开柜时门本来就一直开着），
     * 只记日志不刷起点——起点被刷掉就会把“开了 30 分钟”变成“刚开”，容错与计费跟着错。
     */
    public void afterDoorOpened(BizCompartment slot, Long customerId) {
        LocalDateTime now = LocalDateTime.now();
        if (slotMapper.markDoorOpen(slot.getId(), customerId, now) == 0) {
            log.debug("门已经是开着的，起点保持不动 slotNo={}", slot.getSlotNo());
        }
        recordEvidence(BizItemEvidence.Moment.DOOR_OPENED, slot, "开门采集：此时柜内应为空，作为后续比对的基准");
    }

    /**
     * 落一条证据记录（19C）。<b>不写 photoRef</b>：我们是虚拟设备，没有摄像头，
     * 宁可留空也不能存一个不存在的图片引用——那会比没记录更坏（业务员会去调一张不存在的图）。
     */
    private void recordEvidence(BizItemEvidence.Moment moment, BizCompartment slot, String note) {
        BizItemEvidence evidence = new BizItemEvidence();
        evidence.setTenantId(slot.getTenantId());
        evidence.setCabinetId(slot.getCabinetId());
        evidence.setCompartmentId(slot.getId());
        evidence.setSource(EvidenceSource.DEVICE_PROBE);
        // 三值结论直接带上：测不准就是 UNKNOWN，不折成“没东西”
        Presence presence = slot.getPresence() == null ? Presence.UNKNOWN : slot.getPresence();
        evidence.setPresence(presence);
        evidence.setMoment(moment);
        evidence.setNote(note);
        evidence.setCreateTime(LocalDateTime.now());
        evidenceMapper.insert(evidence);
    }

    /**
     * 另一位用户反馈“我顺手把这个门关上了”（19D 第 4 条的闭环入口）。
     *
     * <p>边界按事实收，不收就不安全：
     * 只受理**当前异常就是“门未关”**的格口（遗留物、待确认、硬件故障都不给外人“关一下就好”，
     * 那会把强锁降级）；关上后仍要过一道检测：柜内报有物就保持异常并转滞留待处理，
     * 不能因为“有人关了门”就把可能有东西的格子放回可售池。
     *
     * <p>不假装做了 AI 图像比对：那需要 19C 的照片记录与标签，现在能对比的只有
     * “上一次的检测结论”与“这一次的检测结论”。
     */
    public String confirmClosedByAnother(Long slotId, Long helperCustomerId) {
        BizCompartment slot = slotMapper.selectById(slotId);
        if (slot == null) {
            throw new BizException(ResultCode.BIZ_ERROR, "格口不存在");
        }
        if (slot.getAnomaly() != CompartmentAnomaly.DOOR_OPEN) {
            throw new BizException(ResultCode.BIZ_ERROR, "这个格口现在不是因为门未关而被占用，无需你处理");
        }
        Sensing sensing = sense(slot.getCabinetId(), slotId);
        if (!sensing.doorClosed()) {
            return "门看起来还没关上，请再推一下确认锁好";
        }
        afterDoorClosed(slot, helperCustomerId);
        BizCompartment after = slotMapper.selectById(slotId);
        recordEvidence(BizItemEvidence.Moment.HELPER_CLOSED,
                after == null ? slot : after,
                "他人代关后采集（id=" + helperCustomerId + "）：与代关前的记录比对后才判定能不能放回可售");
        // 代关的人要进台账：将来争辩“谁关的、为什么关”时这是唯一依据
        logFault(slot, FaultType.Action.RECOVER,
                "由另一位用户（id=" + helperCustomerId + "）代关门，转正常前先做柜内检测");
        if (sensing.presence() == Presence.PRESENT) {
            markContentLeft(slotId, "新用户代关门后检测到柜内仍有物品（由 id=" + helperCustomerId + " 关闭）");
            return "门已关上，但柜内检测到有物品，已转人工处理，谢谢你";
        }
        tryAutoRecover(slotId, sensing);
        workOrders.noteStep(slotId, com.wherelee.cabinet.domain.enums.WorkOrderType.DOOR_NOT_CLOSED,
                "HELPER_CLOSED", "由另一位用户（id=" + helperCustomerId + "）代关，检测无物：异常已解除");
        return "格口已恢复正常，谢谢你顺手关上";
    }

    /**
     * 这台暂存柜上“只差有人把门关上”的格口（19D）。
     *
     * <p>只包 DOOR_OPEN 一类：待确认、遗留物、硬件故障都不该出现在“顺手关上”的列表里
     * （那不是关门能解决的事，把它们列进去等于让用户替平台做安全判定）。
     */
    public java.util.List<ClosableSlot> closableSlots(Long cabinetId) {
        return slotMapper.selectList(Wrappers.<BizCompartment>lambdaQuery()
                        .eq(BizCompartment::getCabinetId, cabinetId)
                        .eq(BizCompartment::getAnomaly, CompartmentAnomaly.DOOR_OPEN)
                        .last("limit 20")).stream()
                .map(s -> new ClosableSlot(s.getId(), s.getSlotNo(), s.getSizeType() == null ? null : s.getSizeType().name()))
                .toList();
    }

    /** 给客户端的“可代关”清单：带 id 是因为确认时要用它寻址，而不让客户端再去拼编号。 */
    public record ClosableSlot(Long slotId, String slotNo, String sizeType) {
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
        var type = com.wherelee.cabinet.domain.enums.WorkOrderType.DOOR_NOT_CLOSED;
        int already = workOrders.remindedTimes(slotId, type);
        // 看管期间每次检测都留一条（“5 分钟检测到有物时再来一张”就落在这里）
        recordEvidence(BizItemEvidence.Moment.WATCH_DETECT, slot,
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
     * 门关了：清掉开点、记下关门时刻与关的人。
     *
     * @return true 表示这次真的把"开着"关成了"关上"；false 表示门本来就关着（幂等重跑）
     */
    public boolean afterDoorClosed(BizCompartment slot, Long customerId) {
        int moved = slotMapper.markDoorClosed(slot.getId(), customerId, LocalDateTime.now());
        if (moved == 0) {
            log.debug("关门事件重复到达，忽略 slotNo={}", slot.getSlotNo());
        }
        recordEvidence(BizItemEvidence.Moment.DOOR_CLOSED, slot, "关门采集：结单与遗留判定的主要凭据");
        // “遗留与否”只能在这个时刻判：人已结完单、取完东西、把门关上了（定-17，2026-10-12 用户点破）。
        // 以前这件事做在“点结束”那一瞬，而那时柜里必然还是他自己的东西，每次正常取物都会被误判成遗弃。
        // 只对已没有活动单绑定的格口判：还在投件/临时开门流程里的格口由那条路径自己判（applyCloseVerified），不在这里抢判
        if (slot.getCurrentOrderId() == null) {
            Sensing sensing = sense(slot.getCabinetId(), slot.getId());
            if (sensing.doorClosed()) {
                if (sensing.presence() == Presence.PRESENT) {
                    markContentLeft(slot.getId(),
                            "取物后门已关上而柜内仍检测到物品：这才是遗留，需人工清走");
                } else if (sensing.presence() == Presence.UNKNOWN || !fresh(sensing)) {
                    markContentUnverified(slot.getId(),
                            "取物后关门但拿不到“柜内已空”的可靠凭据，需现场确认");
                } else {
                    tryAutoRecover(slot.getId(), sensing);
                }
            }
            // 门其实还没关（谎报或时序乱了）：不在这里下结论，留给门未关阶梯与待收敛回收
        }
        return moved == 1;
    }

    /**
     * 门关上后自动解除异常。
     *
     * <p><b>只允许 {@link CompartmentAnomaly#autoRecoverable()} 为真的类型</b>（也就是门未关）。
     * 遗留物与“测不到柜内”必须由人来解除（不变量 I9）——这里少判一次，
     * 就会出现“门一关就把别人还留着东西的格子放回可售池”。
     *
     * <p>解除条件<b>只看门，不看物</b>：DOOR_OPEN 记的就是“门开着”这一件事，
     * 门关了它就该消失。“里面有没有东西”是另一个异常类型的责任（CONTENT_UNVERIFIED / CONTENT_LEFT），
     * 拿它来卡 DOOR_OPEN 的解除，会让所有没装物检的柜机（现实中的大多数）永远解不了异常，
     * 整柜很快全变成“不可分配”。
     */
    public void tryAutoRecover(Long slotId, Sensing sensing) {
        BizCompartment slot = slotMapper.selectById(slotId);
        if (slot == null || slot.getAnomaly() == null || !slot.getAnomaly().autoRecoverable()) {
            return;
        }
        if (!sensing.doorClosed()) {
            return;
        }
        // 门关了不等于格子空了：检到有物就不能解除回可售，否则“把别人的东西卖给下一位”
        // 正是 I12 立约要防的那件事。这条也是“结束取物→关门”之后的真正收口点。
        if (sensing.presence() == Presence.PRESENT) {
            markContentLeft(slotId, "门已关上但柜内仍检测到物品（关门复检）");
            return;
        }
        if (slotMapper.clearAnomaly(slotId) == 0) {
            log.info("异常已被其它路径解除，跳过 slotNo={}", slot.getSlotNo());
            return;
        }
        // 解除也要进台账：运维要能看见“这格何时因什么异常、又是怎么好的”，
        // 只记开始不记结束的事故记录没法用来优化任何事
        logFault(slot, FaultType.Action.RECOVER, "门已关闭，异常自动解除：" + slot.getAnomalyReason());
        // 19B：“事实自己消失了”不等于“人处理过了”：工单转 AUTO_CLOSED 留底而不删除、不记成结论
        workOrders.autoClose(slotId, com.wherelee.cabinet.domain.enums.WorkOrderType.DOOR_NOT_CLOSED);
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
    private void markAnomalyAndLog(Long slotId, CompartmentAnomaly anomaly, String reason) {
        LocalDateTime now = LocalDateTime.now();
        slotMapper.markAnomaly(slotId, anomaly, now, reason);
        BizCompartment slot = slotMapper.selectById(slotId);
        if (slot == null) {
            log.warn("标异常时格口已不存在 slotId={} anomaly={}", slotId, anomaly);
            return;
        }
        logFault(slot, anomaly.autoRecoverable() ? FaultType.Action.ALARM : FaultType.Action.MARK_UNAVAILABLE, reason);
        // 19B：异常一旦落下就同时开一张工单（开单幂等，重复标同一种异常不会刷出多张）。
        // 放在写完当前态与流水之后：工单是“有人该管”，不是异常本身的一部分，
        // 它失败不能让格口不回写（否则开单问提会拖挂主链路）。
        intake.onAnomaly(slotId, anomaly, reason);
    }

    private void logFault(BizCompartment slot, FaultType.Action action, String reason) {
        BizFaultEvent event = new BizFaultEvent();
        event.setCabinetId(slot.getCabinetId());
        event.setSlotId(slot.getId());
        event.setFaultType(FaultType.DOOR_NOT_CLOSED);
        event.setAction(action);
        event.setFailCount(0);
        event.setReason(reason);
        faultMapper.insert(event);
    }

    /**
     * 格口转入“遗留物”异常：关门复检有物 / 代关检测有物 / 下一位上报，三个入口共用。
     *
     * <p>标异常与开单由 {@link #markAnomalyAndLog} 负责；这里额外补一步
     * <b>通知物主</b>（第 21 刀，用户定：自动发站内消息、业务员可选打电话）——
     * 发现遗留物的那一刻，最该知道的人就是把它落在这里的那位。
     */
    public void markContentLeft(Long slotId, String reason) {
        markAnomalyAndLog(slotId, CompartmentAnomaly.CONTENT_LEFT, reason);
        notifyOwnerForLeftover(slotId);
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
            Long workOrderId = workOrders.openIdOf(slotId, WorkOrderType.ITEM_LEFT);
            if (workOrderId == null) {
                return;
            }
            notices.emit(slot.getTenantId(), NoticePort.CHANNEL_MESSAGE, "LEFTOVER_OWNER",
                    "WO" + workOrderId + ":OWNER_MSG",
                    "你的暂存柜格口 " + slot.getSlotNo() + " 检测到遗留物品，请到柜取回。"
                            + "取回路径：小程序-我的寄存单-该订单-取回物品",
                    previous.getCustomerId(), workOrderId, previous.getOrderNo());
        } catch (RuntimeException e) {
            // 开单已成功，通知失败只记不抛：主链路（异常+工单）已闭环，触达下一轮再试
            log.error("遗留物通知物主失败（不阻断主链路）slotId={}", slotId, e);
        }
    }

    /** 物主取回复检后的证据采集（第 21 刀新增采集点，与四个既有采集点同表同规）。 */
    public void recordRetrievalEvidence(BizCompartment slot, String note) {
        recordEvidence(BizItemEvidence.Moment.RETRIEVED_CHECK, slot, note);
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

    /** 人工清柜完成：把这次解除落成 RECOVER 事件（状态列只存当前值，回答不了“谁什么时候清的”）。 */
    public void recordManualRecovery(Long slotId, String note) {
        BizCompartment slot = slotMapper.selectById(slotId);
        if (slot == null) {
            return;
        }
        logFault(slot, FaultType.Action.RECOVER, "人工清柜解除异常：" + note);
    }

    /**
     * 后台强制开柜：门真的被开了，所以记下开门起点（它因此自动不可分配，正是开柜期间想要的状态）。
     *
     * <p><b>不拿 DOOR_OPEN 去盖掉已有的更强锁</b>：遗留物格口被打开取东西时，门只是“暂时开着”，
     * 而“里面有别人东西”这件事不会因开一次门就消失。降级会让格子在下次关门时变回可卖。
     */
    public void markForcedOpen(Long slotId, Long adminId, String reason) {
        BizCompartment slot = slotMapper.selectById(slotId);
        if (slot == null) {
            return;
        }
        afterDoorOpened(slot, null);
        if (slot.getAnomaly() == null) {
            markAnomalyAndLog(slotId, CompartmentAnomaly.DOOR_OPEN, "后台强制开柜，柜门开启中");
        }
        logFault(slot, FaultType.Action.ALARM,
                "后台强制开柜：操作人 admin:" + adminId + "，事由：" + reason);
    }

    public BizCompartment reload(Long slotId) {
        return slotMapper.selectById(slotId);
    }
}
