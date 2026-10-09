package com.wherelee.cabinet.application.storage;

import com.wherelee.cabinet.application.device.DeviceChannel;
import com.wherelee.cabinet.domain.entity.BizCompartment;
import com.wherelee.cabinet.domain.entity.BizFaultEvent;
import com.wherelee.cabinet.domain.enums.CompartmentAnomaly;
import com.wherelee.cabinet.domain.enums.FaultType;
import com.wherelee.cabinet.domain.enums.Presence;
import com.wherelee.cabinet.infrastructure.mapper.BizCompartmentMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizFaultEventMapper;
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
     * 物检结论的可用时效（分钟）。
     *
     * <p>为什么必须有：设备"看了一眼"的时刻不等于"现在"。没有这个上限，
     * 一次三天前的"空柜"读数可以一直替这个格口作证，直到有人把它卖给下一位用户。
     */
    @Value("${cabinet.sensor.presence-max-age-minutes:2}")
    private int presenceMaxAgeMinutes;

    public CompartmentStateService(BizCompartmentMapper slotMapper, BizFaultEventMapper faultMapper,
                                   DeviceChannel channel) {
        this.slotMapper = slotMapper;
        this.faultMapper = faultMapper;
        this.channel = channel;
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
    }

    /** 容错期满而门还开着：这时才真的是异常（计费已经起计，必须有人看见）。 */
    public void markDoorOpenPastTolerance(Long slotId, long openMinutes) {
        markAnomalyAndLog(slotId, CompartmentAnomaly.DOOR_OPEN,
                "柜门已开 " + openMinutes + " 分钟仍未关闭，计费已起计，直到关门或用户远程结束");
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
        if (slotMapper.clearAnomaly(slotId) == 0) {
            log.info("异常已被其它路径解除，跳过 slotNo={}", slot.getSlotNo());
            return;
        }
        // 解除也要进台账：运维要能看见“这格何时因什么异常、又是怎么好的”，
        // 只记开始不记结束的事故记录没法用来优化任何事
        logFault(slot, FaultType.Action.RECOVER, "门已关闭，异常自动解除：" + slot.getAnomalyReason());
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

    /** 用户声明放弃柜内物品后：格口转遗留物异常（订单可以结束，但这个格子不能再卖）。 */
    public void markContentLeft(Long slotId, String reason) {
        markAnomalyAndLog(slotId, CompartmentAnomaly.CONTENT_LEFT, reason);
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
