package com.wherelee.cabinet.storage.app;

import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.OrderStatus;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import com.wherelee.cabinet.locker.api.Presence;
import com.wherelee.cabinet.locker.api.Sensing;
import com.wherelee.cabinet.locker.api.SlotAllocator;
import com.wherelee.cabinet.locker.api.SlotPoolPort;
import com.wherelee.cabinet.locker.api.SlotPort;
import com.wherelee.cabinet.storage.api.FundPort;
import com.wherelee.cabinet.storage.api.StorageOrderView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderReleaseService {

    private static final Logger log = LoggerFactory.getLogger(OrderReleaseService.class);

    private final OrderLookup lookup;
    private final BizStorageOrderMapper orderMapper;
    private final SlotPoolPort slotPool;
    private final SlotPort states;
    private final FundPort funds;
    private final SlotAllocator allocator;

    public OrderReleaseService(OrderLookup lookup,
                               BizStorageOrderMapper orderMapper,
                               SlotPoolPort slotPool,
                               SlotPort states,
                               FundPort funds,
                               SlotAllocator allocator) {
        this.lookup = lookup;
        this.orderMapper = orderMapper;
        this.slotPool = slotPool;
        this.states = states;
        this.funds = funds;
        this.allocator = allocator;
    }

    @Transactional
    public StorageOrderView cancel(Long customerId, String orderNo) {
        BizStorageOrder order = lookup.findOwned(customerId, orderNo);
        Sensing sensing = states.sense(order.getCabinetId(), order.getSlotId());
        if (sensing.presence() == Presence.PRESENT) {
            throw new BizException(ResultCode.BIZ_ERROR,
                    "柜内检测到物品：请先取出再取消（或走“声明放弃物品”结束）");
        }

        if (order.getToleranceUntil() != null && !emptyEvidence(sensing)) {
            states.markContentUnverified(order.getSlotId(),
                    "开过门的单被取消且柜内无法确认，需现场确认后才能重新分配");
        }
        return releaseOrder(order);
    }

    StorageOrderView releaseOrder(BizStorageOrder order) {
        order.transitTo(OrderStatus.CANCELLED);
        boolean released = states.releaseReserved(order.getSlotId(), order.getId());
        if (!released) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "格口状态与订单不一致，请联系运营处理");
        }
        funds.cancelHold(order);
        if (orderMapper.updateById(order) == 0) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "订单已被并发修改，请重试 orderNo=" + order.getOrderNo());
        }
        if ("prealloc".equals(allocator.strategy())) {
            try {
                slotPool.addFree(order.getCabinetId(), order.getSizeType(), order.getSlotId());
            } catch (RuntimeException e) {
                log.warn("格口已回 FREE 但 Redis 空闲集合同步失败（等校准修复）slotId={}", order.getSlotId(), e);
            }
        }
        return new StorageOrderView(order.getOrderNo(), lookup.cabinetNoOf(order.getCabinetId()),
                lookup.slotNoOf(order.getSlotId()), order.getSizeType().name(), order.getStatus().name(),
                0, allocator.strategy(), null);
    }

    private boolean emptyEvidence(Sensing sensing) {
        return sensing.presence() == Presence.ABSENT && states.fresh(sensing);
    }
}
