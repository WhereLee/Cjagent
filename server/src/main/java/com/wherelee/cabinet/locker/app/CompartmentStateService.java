package com.wherelee.cabinet.locker.app;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.device.api.DeviceChannel;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.locker.domain.BizCompartment;
import com.wherelee.cabinet.device.api.EvidenceMoment;
import com.wherelee.cabinet.locker.api.CompartmentAnomaly;
import com.wherelee.cabinet.locker.api.Sensing;
import com.wherelee.cabinet.locker.api.SlotPort;
import com.wherelee.cabinet.device.api.FaultType;
import com.wherelee.cabinet.locker.api.Presence;
import com.wherelee.cabinet.locker.infra.BizCabinetMapper;
import com.wherelee.cabinet.locker.infra.BizCompartmentMapper;
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
 * 如果这里出现"用命令把门关上的"接口，就等于平台替用户把门关了——谎报再也测不出来。
 */
@Service
public class CompartmentStateService implements SlotPort {

    private static final Logger log = LoggerFactory.getLogger(CompartmentStateService.class);

    private final BizCompartmentMapper slotMapper;
    private final BizCabinetMapper cabinetMapper;
    private final DeviceChannel channel;
    /** 异常事件的唯一出口：标异常、开单、留证据、写台账、催办阶梯都从它走。 */
    private final SlotAnomalyPublisher anomalies;

    /**
     * 物检结论的可用时效（分钟）。
     *
     * <p>为什么必须有：设备"看了一眼"的时刻不等于"现在"。没有这个上限，
     * 一次三天前的"空柜"读数可以一直替这个格口作证，直到有人把它卖给下一位用户。
     */
    @Value("${cabinet.sensor.presence-max-age-minutes:2}")
    private int presenceMaxAgeMinutes;

    public CompartmentStateService(BizCompartmentMapper slotMapper, BizCabinetMapper cabinetMapper,
                                   DeviceChannel channel,
                                   SlotAnomalyPublisher anomalies) {
        this.slotMapper = slotMapper;
        this.cabinetMapper = cabinetMapper;
        this.channel = channel;
        this.anomalies = anomalies;
    }

    /**
     * 问一次设备传感器，并把物检结论连同时刻落库。
     *
     * <p>落库而不是只用返回值：业务运维与纠纷还原要能看到"当时设备报了什么、什么时候报的"。
     */
    @Override
    public Sensing sense(Long cabinetId, Long slotId) {
        DeviceChannel.SlotSensor sensor = channel.probe(cabinetId, slotId);
        LocalDateTime at = sensor.sensedAt() == null ? LocalDateTime.now() : sensor.sensedAt();
        if (sensor.presence() != null) {
            slotMapper.recordPresence(slotId, sensor.presence(), at);
        }
        return new Sensing(sensor.doorClosed(), sensor.presence(), at);
    }

    /** 物检结论能不能当凭据（时效在 {@code presence-max-age-minutes} 内）。 */
    @Override
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
        anomalies.recordEvidence(EvidenceMoment.DOOR_OPENED, slot, "开门采集：此时柜内应为空，作为后续比对的基准");
    }

    @Override
    public void afterDoorOpened(Long slotId, Long customerId) {
        afterDoorOpened(requireSlot(slotId), customerId);
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
        anomalies.recordEvidence(EvidenceMoment.HELPER_CLOSED,
                after == null ? slot : after,
                "他人代关后采集（id=" + helperCustomerId + "）：与代关前的记录比对后才判定能不能放回可售");
        // 代关的人要进台账：将来争辩“谁关的、为什么关”时这是唯一依据
        anomalies.recordFault(slot.getId(), FaultType.Action.RECOVER,
                "由另一位用户（id=" + helperCustomerId + "）代关门，转正常前先做柜内检测");
        if (sensing.presence() == Presence.PRESENT) {
            anomalies.markContentLeft(slotId, "新用户代关门后检测到柜内仍有物品（由 id=" + helperCustomerId + " 关闭）");
            return "门已关上，但柜内检测到有物品，已转人工处理，谢谢你";
        }
        tryAutoRecover(slotId, sensing);
        anomalies.noteHelperClosed(slotId,
                "由另一位用户（id=" + helperCustomerId + "）代关，检测无物：异常已解除");
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
    @Override
    public void markDoorOpenPastTolerance(Long slotId, long openMinutes) {
        anomalies.markDoorOpenPastTolerance(slotId, openMinutes);
    }

    /**
     * 门关了：清掉开点、记下关门时刻与关的人。
     *
     * @return true 表示这次真的把"开着"关成了"关上"；false 表示门本来就关着（幂等重跑）
     */
    @Override
    public boolean afterDoorClosed(Long slotId, Long customerId) {
        return afterDoorClosed(requireSlot(slotId), customerId);
    }

    public boolean afterDoorClosed(BizCompartment slot, Long customerId) {
        int moved = slotMapper.markDoorClosed(slot.getId(), customerId, LocalDateTime.now());
        if (moved == 0) {
            log.debug("关门事件重复到达，忽略 slotNo={}", slot.getSlotNo());
        }
        anomalies.recordEvidence(EvidenceMoment.DOOR_CLOSED, slot, "关门采集：结单与遗留判定的主要凭据");
        // “遗留与否”只能在这个时刻判：人已结完单、取完东西、把门关上了（定-17，2026-10-12 用户点破）。
        // 以前这件事做在“点结束”那一瞬，而那时柜里必然还是他自己的东西，每次正常取物都会被误判成遗弃。
        // 只对已没有活动单绑定的格口判：还在投件/临时开门流程里的格口由那条路径自己判（applyCloseVerified），不在这里抢判
        if (slot.getCurrentOrderId() == null) {
            Sensing sensing = sense(slot.getCabinetId(), slot.getId());
            if (sensing.doorClosed()) {
                if (sensing.presence() == Presence.PRESENT) {
                    anomalies.markContentLeft(slot.getId(),
                            "取物后门已关上而柜内仍检测到物品：这才是遗留，需人工清走");
                } else if (sensing.presence() == Presence.UNKNOWN || !fresh(sensing)) {
                    anomalies.markContentUnverified(slot.getId(),
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
    @Override
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
            anomalies.markContentLeft(slotId, "门已关上但柜内仍检测到物品（关门复检）");
            return;
        }
        if (slotMapper.clearAnomaly(slotId) == 0) {
            log.info("异常已被其它路径解除，跳过 slotNo={}", slot.getSlotNo());
            return;
        }
        // 解除也要进台账：运维要能看见“这格何时因什么异常、又是怎么好的”，
        // 只记开始不记结束的事故记录没法用来优化任何事
        anomalies.recordFault(slot.getId(), FaultType.Action.RECOVER, "门已关闭，异常自动解除：" + slot.getAnomalyReason());
        // 19B：“事实自己消失了”不等于“人处理过了”：工单转 AUTO_CLOSED 留底而不删除、不记成结论
        anomalies.autoCloseDoorClosed(slotId);
    }

    /** 物主取回复检后的证据采集（第 21 刀新增采集点，与四个既有采集点同表同规）。 */
    @Override
    public void recordRetrievalEvidence(Long slotId, String note) {
        recordRetrievalEvidence(requireSlot(slotId), note);
    }

    public void recordRetrievalEvidence(BizCompartment slot, String note) {
        anomalies.recordEvidence(EvidenceMoment.RETRIEVED_CHECK, slot, note);
    }

    /**
     * 后台强制开柜：门真的被开了，所以记下开门起点（它因此自动不可分配，正是开柜期间想要的状态）。
     *
     * <p><b>不拿 DOOR_OPEN 去盖掉已有的更强锁</b>：遗留物格口被打开取东西时，门只是“暂时开着”，
     * 而“里面有别人东西”这件事不会因开一次门就消失。降级会让格子在下次关门时变回可卖。
     */
    @Override
    public void markForcedOpen(Long slotId, Long adminId, String reason) {
        BizCompartment slot = slotMapper.selectById(slotId);
        if (slot == null) {
            return;
        }
        afterDoorOpened(slot, null);
        if (slot.getAnomaly() == null) {
            anomalies.markAnomaly(slotId, CompartmentAnomaly.DOOR_OPEN, "后台强制开柜，柜门开启中");
        }
        anomalies.recordFault(slot.getId(), FaultType.Action.ALARM,
                "后台强制开柜：操作人 admin:" + adminId + "，事由：" + reason);
    }

    // ------------------------------------------------------------------
    // 占用与释放：条件更新，看影响行数判定抢到没抢到（与 SlotCandidateQuery 同判据）
    // ------------------------------------------------------------------

    @Override
    public boolean reserve(Long slotId, Long orderId) {
        return slotMapper.reserveSlot(slotId, orderId) == 1;
    }

    @Override
    public boolean occupy(Long slotId, Long orderId) {
        return slotMapper.markOccupied(slotId, orderId) == 1;
    }

    @Override
    public boolean releaseReserved(Long slotId, Long orderId) {
        return slotMapper.releaseSlot(slotId, orderId) == 1;
    }

    @Override
    public boolean releaseOccupied(Long slotId, Long orderId) {
        return slotMapper.releaseOccupiedSlot(slotId, orderId) == 1;
    }

    @Override
    public void markFaulty(Long slotId) {
        slotMapper.markFaulty(slotId);
    }

    @Override
    public void markSlotFault(Long slotId) {
        slotMapper.markSlotFault(slotId);
    }

    @Override
    public void disableCabinet(Long cabinetId) {
        cabinetMapper.disableCabinet(cabinetId);
    }

    // ------------------------------------------------------------------
    // 异常事件：本类只保留“谁在什么时候该标异常”的判断，具体发布交给 SlotAnomalyPublisher
    // ------------------------------------------------------------------

    @Override
    public void markContentLeft(Long slotId, String reason) {
        anomalies.markContentLeft(slotId, reason);
    }

    @Override
    public void markContentUnverified(Long slotId, String reason) {
        anomalies.markContentUnverified(slotId, reason);
    }

    @Override
    public void markSensorConflict(Long slotId, String reason) {
        anomalies.markSensorConflict(slotId, reason);
    }

    @Override
    public void recordManualRecovery(Long slotId, String note) {
        anomalies.recordManualRecovery(slotId, note);
    }

    @Override
    public boolean clearAnomaly(Long slotId) {
        return slotMapper.clearAnomaly(slotId) == 1;
    }

    public BizCompartment reload(Long slotId) {
        return slotMapper.selectById(slotId);
    }

    private BizCompartment requireSlot(Long slotId) {
        BizCompartment slot = slotMapper.selectById(slotId);
        if (slot == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "格口不存在");
        }
        return slot;
    }
}
