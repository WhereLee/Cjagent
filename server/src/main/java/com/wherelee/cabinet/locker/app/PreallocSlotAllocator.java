package com.wherelee.cabinet.locker.app;

import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.locker.api.SizeType;
import com.wherelee.cabinet.locker.api.SlotAllocator;
import com.wherelee.cabinet.locker.api.SlotAllocator.AllocatedSlot;
import com.wherelee.cabinet.locker.api.SlotPoolPort;
import com.wherelee.cabinet.locker.infra.BizCompartmentMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@ConditionalOnProperty(name = "cabinet.alloc.strategy", havingValue = "prealloc")
public class PreallocSlotAllocator implements SlotAllocator {

    private final SlotPoolPort slotPool;
    private final BizCompartmentMapper slotMapper;

    public PreallocSlotAllocator(SlotPoolPort slotPool, BizCompartmentMapper slotMapper) {
        this.slotPool = slotPool;
        this.slotMapper = slotMapper;
    }

    @Override
    public AllocatedSlot allocate(Long cabinetId, SizeType required, Long orderId) {
        List<SizeType> acceptable = SizeType.acceptanceOrder(required);

        SlotPoolPort.Popped popped = slotPool.tryPop(cabinetId, required);
        if (popped == null) {
            throw new BizException(ResultCode.SLOT_UNAVAILABLE, "该柜机没有可用格口");
        }

        if (slotMapper.reserveSlot(popped.slotId(), orderId) != 1) {
            throw new BizException(ResultCode.SLOT_RACE_LOST, "格口刚刚被占用，请重试");
        }

        return new AllocatedSlot(popped.slotId(), popped.size(), acceptable.indexOf(popped.size()) + 1);
    }

    @Override
    public String strategy() {
        return "prealloc";
    }
}
