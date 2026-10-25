package com.wherelee.cabinet.storage.app;

import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.device.api.CommandAction;
import com.wherelee.cabinet.device.api.CommandOutcome;
import com.wherelee.cabinet.device.api.CommandPort;
import com.wherelee.cabinet.device.api.FaultType;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.OrderCloseReason;
import com.wherelee.cabinet.domain.enums.OrderStatus;
import com.wherelee.cabinet.locker.api.CabinetSnapshot;
import com.wherelee.cabinet.locker.api.SlotAllocator;
import com.wherelee.cabinet.locker.api.SlotPort;
import com.wherelee.cabinet.locker.api.SlotQueryPort;
import com.wherelee.cabinet.locker.api.SlotSnapshot;
import com.wherelee.cabinet.servicedesk.api.WorkOrderPort;
import com.wherelee.cabinet.storage.api.FundPort;
import com.wherelee.cabinet.storage.api.StorageOrderView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
public class OrderPickupFlowService {

    private static final Logger log = LoggerFactory.getLogger(OrderPickupFlowService.class);

    private final OrderLookup lookup;
    private final OrderVoucherService vouchers;
    private final FundPort funds;
    private final CommandPort deviceCommands;
    private final SlotQueryPort slotQueries;
    private final SlotPort states;
    private final WorkOrderPort workOrderPort;
    private final SlotAllocator allocator;
    private final TransactionTemplate newTx;

    public OrderPickupFlowService(OrderLookup lookup,
                                  OrderVoucherService vouchers,
                                  FundPort funds,
                                  CommandPort deviceCommands,
                                  SlotQueryPort slotQueries,
                                  SlotPort states,
                                  WorkOrderPort workOrderPort,
                                  SlotAllocator allocator,
                                  TransactionTemplate txTemplate) {
        this.lookup = lookup;
        this.vouchers = vouchers;
        this.funds = funds;
        this.deviceCommands = deviceCommands;
        this.slotQueries = slotQueries;
        this.states = states;
        this.workOrderPort = workOrderPort;
        this.allocator = allocator;
        this.newTx = new TransactionTemplate(txTemplate.getTransactionManager());
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public StorageOrderView pickup(Long customerId, String orderNo) {
        return settleAndOpen(customerId, orderNo, false, null);
    }

    public StorageOrderView pickup(Long customerId, String orderNo, String voucherCode) {
        return settleAndOpen(customerId, orderNo, true, voucherCode);
    }

    private StorageOrderView settleAndOpen(Long customerId, String orderNo,
                                           boolean withVoucher, String voucherCode) {
        StorageOrderView settled = newTx.execute(status -> {
            BizStorageOrder order = lookup.findOwned(customerId, orderNo);
            if (withVoucher) {
                vouchers.verify(order, voucherCode);
            }
            return finish(customerId, orderNo, OrderCloseReason.NORMAL);
        });
        return openForPickup(customerId, orderNo, settled);
    }

    private StorageOrderView openForPickup(Long customerId, String orderNo, StorageOrderView settled) {
        BizStorageOrder order = lookup.findOwned(customerId, orderNo);
        CabinetSnapshot cabinet = slotQueries.cabinet(order.getCabinetId()).orElse(null);
        SlotSnapshot slot = slotQueries.slot(order.getSlotId()).orElse(null);
        if (cabinet == null || slot == null) {
            log.error("结算后取物开门缺少柜机或格口信息 orderNo={} cabinetId={} slotId={}",
                    orderNo, order.getCabinetId(), order.getSlotId());
            states.markContentUnverified(order.getSlotId(),
                    "结算后无法下发取物开门（柜机或格口信息缺失），物品仍在柜内，需现场确认");
            return settled.withMessage("柜机信息不完整，门未打开：请找现场工作人员协助取物");
        }

        if (order.getVoucherLockedAt() != null) {
            states.markContentUnverified(order.getSlotId(),
                    "结算后因取件码连错锁定而未能开门取物，物品仍在柜内，需客服核验后开门");
            return settled.withMessage("本单取件码连续输错已被锁定，柜门未打开："
                    + "可在订单里重置取件码解锁后取物，或联系客服核验");
        }

        long attempts = countAttempts(orderNo, CommandAction.OPEN);
        CommandOutcome outcome;
        try {
            outcome = deviceCommands.dispatch(cabinet.id(), order.getSlotId(),
                    CommandAction.OPEN, order.getId(),
                    orderNo + ":PICKUP:" + (attempts + 1));
        } catch (RuntimeException offline) {
            log.error("结算后取物开门下发失败 orderNo={}: {}", orderNo, offline.getMessage());
            states.markContentUnverified(order.getSlotId(),
                    "结算后取物开门下发失败（柜机离线或通道异常），物品仍在柜内，需现场开门");
            workOrderPort.openDeviceFault(order, true,
                    "用户已结束订单但取物开门下发失败（柜机离线或通道异常），需现场协助开门",
                    "取物开门无响应，已标格口异常", LocalDateTime.now().plusMinutes(10));
            return settled.withMessage("柜机现在连不上，门没打开：已通知现场人员，请联系客服或现场工作人员");
        }

        if (outcome.faultType() == FaultType.OPEN_FAILED) {
            log.warn("结算后取物开门被柜机明确拒绝 orderNo={}", orderNo);
            states.markContentUnverified(order.getSlotId(),
                    "用户已结束订单但柜机明确回报打不开门：物品仍在柜内，需现场开门核验");
            workOrderPort.openDeviceFault(order, true,
                    "用户已结束订单但柜机明确回报打不开门，物品仍在柜内",
                    "取物开门失败，需现场开门", LocalDateTime.now().plusMinutes(10));
            return settled.withMessage("这个柜口打不开：已通知现场人员处理，请联系客服或现场工作人员");
        }

        states.afterDoorOpened(slot.id(), customerId);
        return settled.withMessage(outcome.businessSuccess()
                ? "柜门已打开，请取走物品并随手关好柜门"
                : "已下发开门，请确认柜门已开；若没反应请联系现场工作人员");
    }

    private StorageOrderView finish(Long customerId, String orderNo, OrderCloseReason reason) {
        BizStorageOrder order = lookup.findOwned(customerId, orderNo);
        if (order.getStatus() == OrderStatus.CLOSED || order.getStatus() == OrderStatus.CANCELLED) {
            throw new BizException(ResultCode.BIZ_ERROR, "这单已经结束了：" + order.getStatus());
        }
        if (order.getStatus() == OrderStatus.RESERVED) {
            throw new BizException(ResultCode.BIZ_ERROR, "柜门还没打开过，无需结束：请直接取消防错");
        }
        LocalDateTime settleEnd = LocalDateTime.now();
        long actualMinutes = order.getStartedAt() == null ? 0L
                : Math.max(0L, Duration.between(order.getStartedAt(), settleEnd).toMinutes());
        funds.settle(order, actualMinutes, reason);

        CabinetSnapshot cabinet = slotQueries.cabinet(order.getCabinetId()).orElse(null);
        return new StorageOrderView(order.getOrderNo(), cabinet == null ? "-" : cabinet.cabinetNo(),
                lookup.slotNoOf(order.getSlotId()), order.getSizeType().name(), order.getStatus().name(),
                0, allocator.strategy(), null);
    }

    private long countAttempts(String orderNo, CommandAction action) {
        return deviceCommands.countAttempts(orderNo + ":" + action.name() + ":");
    }
}
