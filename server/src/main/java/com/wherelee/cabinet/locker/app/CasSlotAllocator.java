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
@ConditionalOnProperty(name = "cabinet.alloc.strategy", havingValue = "cas")
public class CasSlotAllocator implements SlotAllocator {

    public static final int MAX_ATTEMPTS = 6;

    private final BizCompartmentMapper slotMapper;

    public CasSlotAllocator(BizCompartmentMapper slotMapper) {
        this.slotMapper = slotMapper;
    }

    @Override
    public AllocatedSlot allocate(Long cabinetId, SizeType required, Long orderId) {
        List<SizeType> acceptable = SizeType.acceptanceOrder(required);

        List<BizCompartment> candidates = slotMapper.selectList(
                SlotCandidateQuery.assignable(cabinetId, acceptable));

        if (candidates.isEmpty()) {
            throw new BizException(ResultCode.SLOT_UNAVAILABLE, "该柜机没有可用格口");
        }

        List<BizCompartment> ordered = candidates.stream()
                .sorted(Comparator.comparingInt(s -> acceptable.indexOf(s.getSizeType())))
                .toList();

        int tried = 0;
        for (BizCompartment candidate : ordered) {
            if (tried >= MAX_ATTEMPTS) {
                break;
            }
            tried++;
            if (slotMapper.reserveSlot(candidate.getId(), orderId) == 1) {
                return new AllocatedSlot(candidate.getId(), candidate.getSizeType(), tried);
            }
        }

        long stillFree = slotMapper.selectCount(SlotCandidateQuery.assignable(cabinetId, acceptable));
        throw new BizException(stillFree > 0 ? ResultCode.SLOT_RACE_LOST : ResultCode.SLOT_UNAVAILABLE,
                stillFree > 0 ? "格口刚刚被占用，请重试" : "该柜机没有可用格口");
    }

    @Override
    public String strategy() {
        return "cas";
    }
}
