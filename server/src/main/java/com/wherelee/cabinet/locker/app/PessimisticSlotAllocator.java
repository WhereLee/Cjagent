package com.wherelee.cabinet.locker.app;

import com.wherelee.cabinet.locker.api.SlotAllocator;
import com.wherelee.cabinet.locker.api.SlotAllocator.AllocatedSlot;

import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.locker.domain.BizCompartment;
import com.wherelee.cabinet.locker.api.SizeType;
import com.wherelee.cabinet.locker.infra.BizCompartmentMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
@ConditionalOnProperty(name = "cabinet.alloc.strategy", havingValue = "pessimistic", matchIfMissing = true)
public class PessimisticSlotAllocator implements SlotAllocator {

    private final BizCompartmentMapper slotMapper;

    public PessimisticSlotAllocator(BizCompartmentMapper slotMapper) {
        this.slotMapper = slotMapper;
    }

    @Override
    public AllocatedSlot allocate(Long cabinetId, SizeType required, Long orderId) {
        List<SizeType> acceptable = SizeType.acceptanceOrder(required);

        List<BizCompartment> candidates = slotMapper.selectList(
                SlotCandidateQuery.assignable(cabinetId, acceptable)
                        .last("for update"));

        if (candidates.isEmpty()) {
            throw new BizException(ResultCode.SLOT_UNAVAILABLE, "该柜机没有可用格口");
        }

        BizCompartment chosen = candidates.stream()
                .min(Comparator.comparingInt(s -> acceptable.indexOf(s.getSizeType())))
                .orElseThrow();

        int hit = slotMapper.reserveSlot(chosen.getId(), orderId);
        if (hit == 0) {
            throw new BizException(ResultCode.SLOT_RACE_LOST, "格口分配冲突，请重试");
        }
        return new AllocatedSlot(chosen.getId(), chosen.getSizeType(), 1);
    }

    @Override
    public String strategy() {
        return "pessimistic";
    }
}
