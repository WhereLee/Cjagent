package com.wherelee.cabinet.storage.app;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.device.api.CommandAction;
import com.wherelee.cabinet.device.api.CommandOutcome;
import com.wherelee.cabinet.device.api.CommandPort;
import com.wherelee.cabinet.device.api.FaultType;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.locker.api.CabinetSnapshot;
import com.wherelee.cabinet.locker.api.CompartmentAnomaly;
import com.wherelee.cabinet.locker.api.Presence;
import com.wherelee.cabinet.locker.api.Sensing;
import com.wherelee.cabinet.locker.api.SlotPort;
import com.wherelee.cabinet.locker.api.SlotQueryPort;
import com.wherelee.cabinet.locker.api.SlotSnapshot;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.servicedesk.api.WorkOrderType;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class LeftoverRetrieveService implements com.wherelee.cabinet.storage.api.LeftoverPort {

    private static final Logger log = LoggerFactory.getLogger(LeftoverRetrieveService.class);

    private final BizStorageOrderMapper orderMapper;
    private final SlotQueryPort slots;
    private final SlotPort slotStates;
    private final CommandPort deviceCommands;
    private final com.wherelee.cabinet.servicedesk.api.WorkOrderPort workOrders;
    private final TransactionTemplate newTx;

    public LeftoverRetrieveService(BizStorageOrderMapper orderMapper, SlotQueryPort slots,
                                   SlotPort slotStates, CommandPort deviceCommands,
                                   com.wherelee.cabinet.servicedesk.api.WorkOrderPort workOrders,
                                   TransactionTemplate txTemplate) {
        this.orderMapper = orderMapper;
        this.slots = slots;
        this.slotStates = slotStates;
        this.deviceCommands = deviceCommands;
        this.workOrders = workOrders;
        this.newTx = new TransactionTemplate(txTemplate.getTransactionManager());
    }

    public String openForRetrieval(Long customerId, String orderNo) {
        BizStorageOrder order = findOwnedTerminal(customerId, orderNo);
        SlotSnapshot slot = slots.slot(order.getSlotId()).orElse(null);
        if (slot == null || !CompartmentAnomaly.CONTENT_LEFT.name().equals(slot.anomaly())) {
            throw new BizException(ResultCode.BIZ_ERROR, "这个订单没有待取回的遗留物品");
        }
        if (slot.currentOrderId() != null) {
            throw new BizException(ResultCode.BIZ_ERROR, "这个格口正在被使用，请稍后再试或联系客服");
        }
        if (slot.doorOpen()) {
            return "柜门已经开着：请直接取走你的物品并关好柜门，然后点\"我取完了\"";
        }
        CabinetSnapshot cabinet = slots.cabinet(order.getCabinetId()).orElse(null);
        if (cabinet == null) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "柜机信息缺失，请联系客服处理");
        }

        CommandOutcome outcome;
        try {
            outcome = deviceCommands.dispatch(cabinet.id(), order.getSlotId(), CommandAction.OPEN,
                    order.getId(), orderNo + ":LEFTOVER:" + (countAttempts(orderNo) + 1));
        } catch (RuntimeException offline) {
            log.warn("遗留物取回开门下发失败 orderNo={}: {}", orderNo, offline.getMessage());
            throw new BizException(ResultCode.BIZ_ERROR, "柜机现在连不上，请稍后再试；问题持续请联系客服");
        }

        if (outcome.faultType() == FaultType.OPEN_FAILED) {
            log.warn("遗留物取回开门被柜机明确拒绝 orderNo={} slotId={}", orderNo, order.getSlotId());
            return "这个柜口打不开：请稍后再试一次，或联系客服安排现场开门";
        }
        if (outcome.businessSuccess()) {
            slotStates.afterDoorOpened(order.getSlotId(), customerId);
            log.info("遗留物取回开门成功 orderNo={} slotId={} customer={}", orderNo, order.getSlotId(), customerId);
            return "柜门已打开，请取走你的物品并关好柜门，然后点\"我取完了\"";
        }
        return "已下发开门指令，请确认柜门是否打开；没打开请稍后再试或联系客服";
    }

    public String confirmRetrieved(Long customerId, String orderNo) {
        BizStorageOrder order = findOwnedTerminal(customerId, orderNo);
        SlotSnapshot slot = slots.slot(order.getSlotId()).orElse(null);
        if (slot == null || !CompartmentAnomaly.CONTENT_LEFT.name().equals(slot.anomaly())) {
            throw new BizException(ResultCode.BIZ_ERROR, "这个订单没有待取回的遗留物品");
        }
        Sensing sensing = slotStates.sense(order.getCabinetId(), order.getSlotId());
        if (!sensing.doorClosed()) {
            return "柜门还开着：请先关好柜门，再点\"我取完了\"";
        }
        if (sensing.presence() == Presence.PRESENT) {
            return "柜内检测到仍有物品：请确认是否全部取走，关好柜门后再点一次\"我取完了\"";
        }
        if (sensing.presence() != Presence.ABSENT || !slotStates.fresh(sensing)) {
            return "这次没能确认柜内已空（设备没给出可靠结论）：格口保持锁定，请联系客服安排现场确认";
        }
        slotStates.afterDoorClosed(order.getSlotId(), customerId);

        newTx.executeWithoutResult(status -> {
            if (!slotStates.clearAnomaly(order.getSlotId())) {
                throw new BizException(ResultCode.SYSTEM_ERROR, "格口状态刚刚被其他人处理，请刷新后重试");
            }
            slotStates.recordRetrievalEvidence(order.getSlotId(),
                    "物主自助取回复检：柜内已空，格口回可售（订单 " + orderNo + "）");
            slotStates.recordManualRecovery(order.getSlotId(),
                    "物主自助取回，复检柜内无物（订单 " + orderNo + "）");
            workOrders.noteStep(order.getSlotId(), WorkOrderType.ITEM_LEFT, "RETRIEVED_BY_OWNER",
                    "物主（客户 " + customerId + "）自助取回，复检柜内无物：格口已回可售；工单结论待业务员回填");
        });
        log.info("遗留物已被物主取回 orderNo={} slotId={} customer={}", orderNo, order.getSlotId(), customerId);
        return "已确认取完：柜门已关好、柜内已空，这个格口恢复使用。谢谢你";
    }

    private BizStorageOrder findOwnedTerminal(Long customerId, String orderNo) {
        BizStorageOrder order = orderMapper.selectOne(Wrappers.<BizStorageOrder>lambdaQuery()
                .eq(BizStorageOrder::getOrderNo, orderNo));
        if (order == null || !order.getCustomerId().equals(customerId)) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "寄存单不存在");
        }
        if (!order.getStatus().isTerminal()) {
            throw new BizException(ResultCode.BIZ_ERROR, "这个订单还没结束：进行中的单请走正常的开柜/取件流程");
        }
        return order;
    }

    private long countAttempts(String orderNo) {
        return deviceCommands.countAttempts(orderNo + ":LEFTOVER:");
    }
}
