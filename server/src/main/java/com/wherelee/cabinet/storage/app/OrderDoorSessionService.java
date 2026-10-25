package com.wherelee.cabinet.storage.app;

import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.device.api.CommandAction;
import com.wherelee.cabinet.device.api.CommandOutcome;
import com.wherelee.cabinet.device.api.CommandPort;
import com.wherelee.cabinet.device.api.CommandState;
import com.wherelee.cabinet.device.api.FaultType;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.OrderStatus;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import com.wherelee.cabinet.locker.api.CabinetSnapshot;
import com.wherelee.cabinet.locker.api.SlotAllocator;
import com.wherelee.cabinet.locker.api.SlotQueryPort;
import com.wherelee.cabinet.locker.api.SlotSwapPort;
import com.wherelee.cabinet.servicedesk.api.WorkOrderPort;
import com.wherelee.cabinet.storage.api.StorageOrderView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class OrderDoorSessionService {

    private static final Logger log = LoggerFactory.getLogger(OrderDoorSessionService.class);

    private final OrderLookup lookup;
    private final OrderReleaseService release;
    private final BizStorageOrderMapper orderMapper;
    private final CommandPort deviceCommands;
    private final SlotQueryPort slotQueries;
    private final SlotSwapPort slotSwap;
    private final OrderDeviceOutcomeService deviceOutcomes;
    private final OrderVoucherService vouchers;
    private final WorkOrderPort workOrderPort;
    private final SlotAllocator allocator;

    @Value("${cabinet.device.open-retries:2}")
    private long openRetries;

    @Value("${cabinet.device.open-retry-interval-ms:1000}")
    private long openRetryIntervalMs;

    public OrderDoorSessionService(OrderLookup lookup, OrderReleaseService release,
                                  BizStorageOrderMapper orderMapper,
                                  CommandPort deviceCommands, SlotQueryPort slotQueries,
                                  SlotSwapPort slotSwap, OrderDeviceOutcomeService deviceOutcomes,
                                  OrderVoucherService vouchers, WorkOrderPort workOrderPort,
                                  SlotAllocator allocator) {
        this.lookup = lookup;
        this.release = release;
        this.orderMapper = orderMapper;
        this.deviceCommands = deviceCommands;
        this.slotQueries = slotQueries;
        this.slotSwap = slotSwap;
        this.deviceOutcomes = deviceOutcomes;
        this.vouchers = vouchers;
        this.workOrderPort = workOrderPort;
        this.allocator = allocator;
    }

    public StorageOrderView openDoor(Long customerId, String orderNo, CommandAction action) {
        BizStorageOrder order = lookup.findOwned(customerId, orderNo);
        if (action != CommandAction.CLOSE_VERIFY) {
            vouchers.requireNotLocked(order);
        }
        CabinetSnapshot cabinet = slotQueries.cabinet(order.getCabinetId()).orElse(null);
        if (cabinet == null) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "柜机不存在，需人工核对 orderNo=" + orderNo);
        }

        boolean opening = action == CommandAction.OPEN;
        boolean tempOpen = action == CommandAction.OPEN_TEMP;
        boolean verifyClose = action == CommandAction.CLOSE_VERIFY;

        if (opening && order.getStatus() != OrderStatus.RESERVED) {
            throw new BizException(ResultCode.BIZ_ERROR, "当前状态不可开柜：" + order.getStatus());
        }
        if (tempOpen && order.getStatus() != OrderStatus.ACTIVE) {
            throw new BizException(ResultCode.BIZ_ERROR, "只有计费中的订单可以临时开柜");
        }
        if (verifyClose && !deviceOutcomes.mayCloseVerify(order)) {
            throw new BizException(ResultCode.BIZ_ERROR,
                    "没有待关闭的柜门（当前状态 " + order.getStatus() + "）");
        }

        if (opening) {
            prepareHandshake(customerId, order, cabinet);
            order = lookup.findOwned(customerId, orderNo);
        }

        if (opening) {
            order.transitTo(OrderStatus.OPENING);
            if (orderMapper.updateById(order) == 0) {
                throw new BizException(ResultCode.SYSTEM_ERROR, "订单已被并发修改，请重试 orderNo=" + orderNo);
            }
        }

        long attempts = countAttempts(orderNo, action);
        String requestId = orderNo + ":" + action.name() + ":" + (attempts + 1);
        CommandOutcome outcome;
        try {
            outcome = deviceCommands.dispatch(cabinet.id(), order.getSlotId(), action, order.getId(), requestId);
        } catch (RuntimeException e) {
            if (opening) {
                order.transitTo(OrderStatus.RESERVED);
                if (orderMapper.updateById(order) == 0) {
                    BizStorageOrder now = orderMapper.selectById(order.getId());
                    if (now != null && now.getStatus() == OrderStatus.OPENING) {
                        log.error("下发失败后退回 RESERVED 也没成功，订单卡在 OPENING 等待收敛回收 orderNo={}",
                                orderNo);
                    }
                }
            }
            throw e;
        }

        deviceOutcomes.apply(order, action, outcome);
        if (orderMapper.updateById(order) == 0) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "订单已被并发修改，请重试 orderNo=" + orderNo);
        }

        String openSlotNo = lookup.slotNoOf(order.getSlotId());
        String note;
        if (opening && outcome.businessSuccess()) {
            note = vouchers.noteForNewOrder(order.getVoucherCode(), openSlotNo);
        } else if (outcome.message() != null && !outcome.message().isBlank()) {
            note = outcome.message();
        } else if (outcome.state() == CommandState.FAILED) {
            note = "设备明确返回失败，请人工检查柜门或柜机。";
        } else if (outcome.businessSuccess()) {
            note = switch (action) {
                case OPEN_TEMP -> "柜门已重新打开，请尽快完成取放并关好柜门。";
                case CLOSE_VERIFY -> "关门校验通过。";
                default -> "指令执行成功。";
            };
        } else {
            note = "指令已下发，但未收到柜机明确回执；请到柜前确认柜门是否弹开。";
        }
        return new StorageOrderView(order.getOrderNo(), cabinet.cabinetNo(), openSlotNo,
                order.getSizeType().name(), order.getStatus().name(),
                0, allocator.strategy(), note);
    }

    private void prepareHandshake(Long customerId, BizStorageOrder order, CabinetSnapshot cabinet) {
        int tries = 1 + (int) openRetries;
        for (int i = 0; i < tries; i++) {
            if (prepareOnce(order, cabinet) != PrepareResult.FAILED) {
                return;
            }
            if (i < tries - 1) {
                sleepBeforeRetry();
            }
        }

        BizStorageOrder current = lookup.findOwned(customerId, order.getOrderNo());
        if (swapToFreeSlot(current) && prepareOnce(current, cabinet) == PrepareResult.PASSED) {
            return;
        }

        try {
            release.cancel(customerId, current.getOrderNo());
        } catch (RuntimeException releaseFailed) {
            log.error("预备开失败后的退单也没成功，由待收敛回收兜底 orderNo={}: {}",
                    current.getOrderNo(), releaseFailed.getMessage());
        }
        throw new BizException(ResultCode.BIZ_ERROR,
                "这个柜口现在打不开，也没有可用的同规模空格：已为你退单，预收全额退回。");
    }

    private enum PrepareResult { PASSED, FAILED, SILENT }

    private PrepareResult prepareOnce(BizStorageOrder order, CabinetSnapshot cabinet) {
        long seq = countAttempts(order.getOrderNo(), CommandAction.PREPARE);
        var outcome = deviceCommands.dispatch(cabinet.id(), order.getSlotId(),
                CommandAction.PREPARE, order.getId(),
                order.getOrderNo() + ":PREPARE:" + (seq + 1));
        if (outcome.faultType() == FaultType.OPEN_FAILED) {
            return PrepareResult.FAILED;
        }
        if (outcome.businessSuccess()) {
            return PrepareResult.PASSED;
        }
        return PrepareResult.SILENT;
    }

    private boolean swapToFreeSlot(BizStorageOrder order) {
        var swapped = slotSwap.swapIn(order.getCabinetId(), order.getSizeType(),
                order.getId(), order.getSlotId()).orElse(null);
        if (swapped == null) {
            return false;
        }
        Long oldSlotId = order.getSlotId();
        order.setSlotId(swapped.id());
        if (!writeSwappedSlot(order)) {
            log.error("换格写库失败，新格口可能悬空需核对 orderNo={} old={} new={}",
                    order.getOrderNo(), oldSlotId, swapped.id());
            return false;
        }
        log.warn("预备开失败判定格口故障并换格 old={} new={} orderNo={}",
                oldSlotId, swapped.id(), order.getOrderNo());
        workOrderPort.openDeviceFault(order,
                true, "预备开自动重试 " + (1 + (int) openRetries) + " 次柜机均回报开不了，旧格口已摘出可分配池",
                "格口打不开，已改用格口 " + swapped.slotNo(), java.time.LocalDateTime.now().plusHours(2));
        return true;
    }

    private boolean writeSwappedSlot(BizStorageOrder order) {
        if (orderMapper.updateById(order) == 1) {
            return true;
        }
        BizStorageOrder fresh = orderMapper.selectById(order.getId());
        if (fresh == null) {
            return false;
        }
        if (order.getSlotId().equals(fresh.getSlotId())) {
            return true;
        }
        fresh.setSlotId(order.getSlotId());
        return orderMapper.updateById(fresh) == 1;
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(openRetryIntervalMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ResultCode.SYSTEM_ERROR, "重试等待被中断，请重新发起开门");
        }
    }

    private long countAttempts(String orderNo, CommandAction action) {
        return deviceCommands.countAttempts(orderNo + ":" + action.name() + ":");
    }
}
