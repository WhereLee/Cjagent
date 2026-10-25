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

@Service
public class CompartmentStateService implements SlotPort {

    private static final Logger log = LoggerFactory.getLogger(CompartmentStateService.class);

    private final BizCompartmentMapper slotMapper;
    private final BizCabinetMapper cabinetMapper;
    private final DeviceChannel channel;
    private final SlotAnomalyPublisher anomalies;

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

    @Override
    public Sensing sense(Long cabinetId, Long slotId) {
        DeviceChannel.SlotSensor sensor = channel.probe(cabinetId, slotId);
        LocalDateTime at = sensor.sensedAt() == null ? LocalDateTime.now() : sensor.sensedAt();
        if (sensor.presence() != null) {
            slotMapper.recordPresence(slotId, sensor.presence(), at);
        }
        return new Sensing(sensor.doorClosed(), sensor.presence(), at);
    }

    @Override
    public boolean fresh(Sensing sensing) {
        return sensing.sensedAt() != null
                && java.time.Duration.between(sensing.sensedAt(), LocalDateTime.now()).toMinutes()
                <= presenceMaxAgeMinutes;
    }

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

    public java.util.List<ClosableSlot> closableSlots(Long cabinetId) {
        return slotMapper.selectList(Wrappers.<BizCompartment>lambdaQuery()
                        .eq(BizCompartment::getCabinetId, cabinetId)
                        .eq(BizCompartment::getAnomaly, CompartmentAnomaly.DOOR_OPEN)
                        .last("limit 20")).stream()
                .map(s -> new ClosableSlot(s.getId(), s.getSlotNo(), s.getSizeType() == null ? null : s.getSizeType().name()))
                .toList();
    }

    public record ClosableSlot(Long slotId, String slotNo, String sizeType) {
    }

    @Override
    public void markDoorOpenPastTolerance(Long slotId, long openMinutes) {
        anomalies.markDoorOpenPastTolerance(slotId, openMinutes);
    }

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
        }
        return moved == 1;
    }

    @Override
    public void tryAutoRecover(Long slotId, Sensing sensing) {
        BizCompartment slot = slotMapper.selectById(slotId);
        if (slot == null || slot.getAnomaly() == null || !slot.getAnomaly().autoRecoverable()) {
            return;
        }
        if (!sensing.doorClosed()) {
            return;
        }
        if (sensing.presence() == Presence.PRESENT) {
            anomalies.markContentLeft(slotId, "门已关上但柜内仍检测到物品（关门复检）");
            return;
        }
        if (slotMapper.clearAnomaly(slotId) == 0) {
            log.info("异常已被其它路径解除，跳过 slotNo={}", slot.getSlotNo());
            return;
        }
        anomalies.recordFault(slot.getId(), FaultType.Action.RECOVER, "门已关闭，异常自动解除：" + slot.getAnomalyReason());
        anomalies.autoCloseDoorClosed(slotId);
    }

    @Override
    public void recordRetrievalEvidence(Long slotId, String note) {
        recordRetrievalEvidence(requireSlot(slotId), note);
    }

    public void recordRetrievalEvidence(BizCompartment slot, String note) {
        anomalies.recordEvidence(EvidenceMoment.RETRIEVED_CHECK, slot, note);
    }

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
