package com.wherelee.cabinet.storage.app;

import com.wherelee.cabinet.notify.api.NoticeChannel;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.device.api.CommandAction;
import com.wherelee.cabinet.device.api.CommandOutcome;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.OrderStatus;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import com.wherelee.cabinet.locker.api.CabinetSnapshot;
import com.wherelee.cabinet.locker.api.CabinetStatus;
import com.wherelee.cabinet.locker.api.Sensing;
import com.wherelee.cabinet.locker.api.SizeType;
import com.wherelee.cabinet.locker.api.SlotAllocator;
import com.wherelee.cabinet.locker.api.SlotPort;
import com.wherelee.cabinet.locker.api.SlotQueryPort;
import com.wherelee.cabinet.notify.api.NoticePort;
import com.wherelee.cabinet.storage.api.CreateOrderCommand;
import com.wherelee.cabinet.storage.api.FundPort;
import com.wherelee.cabinet.storage.api.StorageOrderView;
import com.wherelee.cabinet.storage.api.VoucherView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class StorageOrderService {

    private static final Logger log = LoggerFactory.getLogger(StorageOrderService.class);

    private final SlotQueryPort slotQueries;
    private final BizStorageOrderMapper orderMapper;
    private final SlotAllocator allocator;
    private final OrderVoucherService vouchers;
    private final FundPort funds;
    private final OrderDeviceOutcomeService deviceOutcomes;
    private final SlotPort states;
    private final BizStorageOrderMapper arrearsMapper;
    private final NoticePort notices;
    private final ObjectProvider<com.wherelee.cabinet.platform.scheduling.DelayTaskService> taskScheduler;

    private final OrderLookup lookup;
    private final OrderReleaseService release;
    private final OrderPickupFlowService pickupFlow;
    private final OrderDoorSessionService doorSession;

    @Value("${cabinet.scheduler.reconcile-minutes:10}")
    private long reconcileMinutes;

    public StorageOrderService(SlotQueryPort slotQueries,
                               BizStorageOrderMapper orderMapper,
                               SlotAllocator allocator,
                               OrderVoucherService vouchers,
                               FundPort funds,
                               OrderDeviceOutcomeService deviceOutcomes,
                               SlotPort states,
                               BizStorageOrderMapper arrearsMapper,
                               ObjectProvider<com.wherelee.cabinet.platform.scheduling.DelayTaskService> taskScheduler,
                               NoticePort notices,
                               OrderLookup lookup,
                               OrderReleaseService release,
                               OrderPickupFlowService pickupFlow,
                               OrderDoorSessionService doorSession) {
        this.slotQueries = slotQueries;
        this.orderMapper = orderMapper;
        this.allocator = allocator;
        this.vouchers = vouchers;
        this.funds = funds;
        this.deviceOutcomes = deviceOutcomes;
        this.states = states;
        this.arrearsMapper = arrearsMapper;
        this.taskScheduler = taskScheduler;
        this.notices = notices;
        this.lookup = lookup;
        this.release = release;
        this.pickupFlow = pickupFlow;
        this.doorSession = doorSession;
    }

    @Transactional
    public StorageOrderView create(Long customerId, CreateOrderCommand command) {
        CabinetSnapshot cabinet = slotQueries.cabinetByNo(command.cabinetNo()).orElse(null);
        if (cabinet == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "柜机不存在：" + command.cabinetNo());
        }
        if (!CabinetStatus.ENABLED.name().equals(cabinet.cabinetStatus())) {
            throw new BizException(ResultCode.BIZ_ERROR, "该柜机暂停服务，请选择邻近柜机");
        }

        SizeType required = parseSize(command.sizeType());
        long owing = arrearsMapper.sumCustomerArrears(customerId);
        if (owing > 0L) {
            throw new BizException(ResultCode.BIZ_ERROR,
                    "您有未缴清的费用（" + owing + " 点），请先在点数页补缴后再下单");
        }
        long activeOrders = orderMapper.selectCount(Wrappers.<BizStorageOrder>lambdaQuery()
                .eq(BizStorageOrder::getCustomerId, customerId)
                .eq(BizStorageOrder::getActiveFlag, 1));
        if (activeOrders > 0) {
            throw new BizException(ResultCode.BIZ_ERROR,
                    "你还有未结束的寄存单：同一时间只能用一个格口，请先取件结束再开新单");
        }
        Long orderId = IdWorker.getId();
        SlotAllocator.AllocatedSlot allocated = allocator.allocate(cabinet.id(), required, orderId);

        BizStorageOrder order = new BizStorageOrder();
        order.setId(orderId);
        order.setOrderNo("SO" + orderId);
        order.setSiteId(cabinet.siteId());
        order.setCabinetId(cabinet.id());
        order.setSlotId(allocated.slotId());
        order.setSizeType(allocated.actualSize());
        order.setCustomerId(customerId);
        order.setVoucherCode(VoucherCodes.newVoucher());
        order.setTempOpenCount(0);
        order.setDepositPoints(0L);
        order.setFrozenPoints(0L);
        order.setSettledPoints(0L);
        order.setArrearsPoints(0L);
        order.initStatus();
        order.transitTo(OrderStatus.RESERVED);

        try {
            funds.holdFunds(order);
            vouchers.insert(order);
        } catch (RuntimeException e) {
            log.error("订单落库失败，格口占用将随事务回滚 orderId={} slotId={} strategy={}",
                    orderId, allocated.slotId(), allocator.strategy(), e);
            throw e;
        }

        var scheduler = taskScheduler.getIfAvailable();
        if (scheduler != null) {
            scheduler.schedule(com.wherelee.cabinet.domain.enums.TaskType.SLOT_RELEASE, order.getOrderNo(),
                    order.getTenantId(), LocalDateTime.now().plusMinutes(reconcileMinutes));
        }

        String createdSlotNo = lookup.slotNoOf(allocated.slotId());
        notices.emit(cabinet.tenantId(),
                NoticeChannel.SYSTEM_MESSAGE,
                "PICKUP_VOUCHER", order.getOrderNo() + ":voucher",
                vouchers.noteForExisting(order.getVoucherCode(), createdSlotNo),
                order.getCustomerId(), null, order.getOrderNo());
        return new StorageOrderView(order.getOrderNo(), cabinet.cabinetNo(), createdSlotNo,
                allocated.actualSize().name(), order.getStatus().name(),
                allocated.triedSlots(), allocator.strategy(),
                vouchers.noteForNewOrder(order.getVoucherCode(), createdSlotNo));
    }

    @Transactional
    public StorageOrderView cancel(Long customerId, String orderNo) {
        return release.cancel(customerId, orderNo);
    }

    public StorageOrderView pickup(Long customerId, String orderNo) {
        return pickupFlow.pickup(customerId, orderNo);
    }

    public StorageOrderView pickup(Long customerId, String orderNo, String voucherCode) {
        return pickupFlow.pickup(customerId, orderNo, voucherCode);
    }

    public StorageOrderView openDoor(Long customerId, String orderNo, CommandAction action) {
        return doorSession.openDoor(customerId, orderNo, action);
    }

    private SizeType parseSize(String value) {
        try {
            return SizeType.of(value);
        } catch (IllegalArgumentException e) {
            throw new BizException(ResultCode.PARAM_INVALID, "尺寸不合法：" + value);
        }
    }

    public String activeStrategy() {
        return allocator.strategy();
    }

    @Transactional
    public VoucherView resetVoucher(Long customerId, String orderNo) {
        BizStorageOrder order = lookup.findOwned(customerId, orderNo);
        if (order.getStatus().isTerminal()) {
            throw new BizException(ResultCode.BIZ_ERROR, "该单已结束，取件码已失效，无需重置");
        }
        String finalCode = vouchers.rotate(order);
        notices.rewrite(orderNo + ":voucher",
                vouchers.noteForExisting(finalCode, lookup.slotNoOf(order.getSlotId())));
        return new VoucherView(orderNo, lookup.cabinetNoOf(order.getCabinetId()), lookup.slotNoOf(order.getSlotId()),
                order.getSizeType().name(), order.getStatus().name(), finalCode, false);
    }

    @Transactional
    public StorageOrderView reportLeftover(Long customerId, String orderNo) {
        BizStorageOrder order = lookup.findOwned(customerId, orderNo);
        if (order.getStatus() != OrderStatus.OPENING && order.getStatus() != OrderStatus.ACTIVE
                && order.getStatus() != OrderStatus.TEMP_OPEN) {
            throw new BizException(ResultCode.BIZ_ERROR, "只有正在使用的格口可以上报遗留物");
        }
        Long slotId = order.getSlotId();
        states.markContentLeft(slotId, "下一位使用者开门发现他人遗留物，需业务运维清柜");
        log.warn("格口遗留物上报 orderNo={} slotId={} 上报人={}", order.getOrderNo(), slotId, customerId);
        return release.releaseOrder(order);
    }

    @Transactional
    public boolean doorWatchTick(String orderNo, Sensing sensing) {
        BizStorageOrder order = orderMapper.selectOne(Wrappers.<BizStorageOrder>lambdaQuery()
                .eq(BizStorageOrder::getOrderNo, orderNo));
        if (order == null || order.getStatus().isTerminal()) {
            return false;
        }
        if (order.getToleranceUntil() == null) {
            log.debug("容错收敛跳过：该单没有开过门 orderNo={}", orderNo);
            return false;
        }
        if (sensing.doorClosed()) {
            deviceOutcomes.applyCloseVerified(order, sensing);
        } else {
            if (order.getStatus() == OrderStatus.OPENING) {
                order.transitTo(OrderStatus.ACTIVE);
            }
            LocalDateTime start = order.getToleranceUntil();
            deviceOutcomes.startBilling(order, start);
            states.markDoorOpenPastTolerance(order.getSlotId(),
                    java.time.Duration.between(start, LocalDateTime.now()).toMinutes());
        }
        if (orderMapper.updateById(order) == 0) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "订单已被并发修改，容错收敛下一轮再来 orderNo=" + orderNo);
        }
        return true;
    }

    @Transactional
    public void convergeDeviceOutcome(Long orderId, CommandAction action, CommandOutcome outcome) {
        BizStorageOrder order = orderMapper.selectById(orderId);
        if (order == null || order.getStatus().isTerminal()) {
            log.info("回扫收敛跳过：单不存在或已终态 orderId={}", orderId);
            return;
        }
        deviceOutcomes.apply(order, action, outcome);
        if (orderMapper.updateById(order) == 0) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "订单已被并发修改，回扫下一轮再来 orderId=" + orderId);
        }
    }

    int registerVoucherWrong(BizStorageOrder order) {
        return vouchers.registerWrong(order);
    }
}
