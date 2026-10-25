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

@Service
public class SlotAnomalyPublisher {

    private static final Logger log = LoggerFactory.getLogger(SlotAnomalyPublisher.class);

    private final BizCompartmentMapper slotMapper;
    private final FaultPort faults;
    private final WorkOrderPort workOrders;
    private final EvidencePort evidence;
    private final OutboxPort outbox;
    private final BizStorageOrderMapper orderMapper;

    @Value("${cabinet.door-watch.first-remind-minutes:3}")
    private long firstRemindMinutes;
    @Value("${cabinet.door-watch.repeat-remind-minutes:5}")
    private long repeatRemindMinutes;
    @Value("${cabinet.door-watch.escalate-minutes:10}")
    private long escalateMinutes;
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

    public void recordEvidence(EvidenceMoment moment, BizCompartment slot, String note) {
        Presence presence = slot.getPresence() == null ? Presence.UNKNOWN : slot.getPresence();
        evidence.record(new EvidenceRecord(slot.getTenantId(), slot.getCabinetId(), slot.getId(),
                moment.name(), "DEVICE_PROBE", presence.name(), note));
    }

    public void markContentLeft(Long slotId, String reason) {
        markAnomalyAndLog(slotId, CompartmentAnomaly.CONTENT_LEFT, reason);
        notifyOwnerForLeftover(slotId);
    }

    public void markContentUnverified(Long slotId, String reason) {
        BizCompartment slot = slotMapper.selectById(slotId);
        if (slot != null && slot.getAnomaly() != null && slot.getAnomaly() != CompartmentAnomaly.DOOR_OPEN) {
            log.debug("已有更强的异常，不降级为待确认 slotNo={} anomaly={}", slot.getSlotNo(), slot.getAnomaly());
            return;
        }
        markAnomalyAndLog(slotId, CompartmentAnomaly.CONTENT_UNVERIFIED, reason);
    }

    public void markSensorConflict(Long slotId, String reason) {
        markAnomalyAndLog(slotId, CompartmentAnomaly.SENSOR_CONFLICT, reason);
    }

    public void markDoorOpenPastTolerance(Long slotId, long openMinutes) {
        markAnomalyAndLog(slotId, CompartmentAnomaly.DOOR_OPEN,
                "柜门已开 " + openMinutes + " 分钟仍未关闭，计费已起计，直到关门或用户结束订单");
        runReminderLadder(slotId, openMinutes);
    }

    public void recordManualRecovery(Long slotId, String note) {
        BizCompartment slot = slotMapper.selectById(slotId);
        if (slot == null) {
            return;
        }
        recordFault(slotId, FaultType.Action.RECOVER, "人工清柜解除异常：" + note);
    }

    public void markAnomaly(Long slotId, CompartmentAnomaly anomaly, String reason) {
        markAnomalyAndLog(slotId, anomaly, reason);
    }

    public void noteHelperClosed(Long slotId, String note) {
        workOrders.noteStep(slotId, WorkOrderType.DOOR_NOT_CLOSED, "HELPER_CLOSED", note);
    }

    public void autoCloseDoorClosed(Long slotId) {
        workOrders.autoClose(slotId, WorkOrderType.DOOR_NOT_CLOSED);
    }

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
        outbox.appendWorkOrderAnomaly(slotId, anomaly, reason, slotId + ":" + anomaly + ":" + now);
    }

    private void runReminderLadder(Long slotId, long openMinutes) {
        BizCompartment slot = slotMapper.selectById(slotId);
        if (slot == null || slot.getAnomaly() != CompartmentAnomaly.DOOR_OPEN) {
            return;
        }
        WorkOrderType type = WorkOrderType.DOOR_NOT_CLOSED;
        int already = workOrders.remindedTimes(slotId, type);
        recordEvidence(EvidenceMoment.WATCH_DETECT, slot,
                "门未关第 " + already + " 次检测：柜内=" + slot.getPresence());
        int totalReminds = 1 + repeatRemindTimes;
        if (already >= totalReminds) {
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
        if (openMinutes < repeatRemindMinutes) {
            return;
        }
        workOrders.remind(slotId, type, "提醒 " + (already + 1) + "/" + totalReminds
                + "：门已开 " + openMinutes + " 分钟，请关门或带走物品后结束");
        if (slot.getPresence() == Presence.PRESENT) {
            workOrders.escalate(slotId, type, "检测到柜内有物而门还开着：升为紧急异常");
        }
    }

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
            outbox.appendOwnerNotice(NoticeChannel.SYSTEM_MESSAGE, "LEFTOVER_OWNER",
                    previous.getOrderNo() + ":OWNER_MSG",
                    "你的暂存柜格口 " + slot.getSlotNo() + " 检测到遗留物品，请到柜取回。"
                            + "取回路径：小程序-我的寄存单-该订单-取回物品",
                    previous.getCustomerId(), workOrderId, previous.getOrderNo());
        } catch (RuntimeException e) {
            log.error("遗留物通知物主失败（不阻断主链路）slotId={}", slotId, e);
        }
    }
}
