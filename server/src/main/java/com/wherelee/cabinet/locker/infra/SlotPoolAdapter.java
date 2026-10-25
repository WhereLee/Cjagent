package com.wherelee.cabinet.locker.infra;

import com.wherelee.cabinet.locker.api.SizeType;
import com.wherelee.cabinet.locker.api.SlotPoolPort;
import com.wherelee.cabinet.locker.app.SlotCandidateQuery;
import com.wherelee.cabinet.locker.domain.BizCompartment;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SlotPoolAdapter implements SlotPoolPort {

    private static final System.Logger log = System.getLogger(SlotPoolAdapter.class.getName());

    private final SlotPreDeductionService preDeduction;
    private final BizCompartmentMapper slotMapper;

    public SlotPoolAdapter(SlotPreDeductionService preDeduction, BizCompartmentMapper slotMapper) {
        this.preDeduction = preDeduction;
        this.slotMapper = slotMapper;
    }

    @Override
    public Popped tryPop(Long cabinetId, SizeType required) {
        return preDeduction.pop(cabinetId, SizeType.acceptanceOrder(required));
    }

    @Override
    public void addFree(Long cabinetId, SizeType sizeType, Long slotId) {
        preDeduction.addFree(cabinetId, sizeType, slotId);
    }

    @Override
    public void syncFreeSet(Long cabinetId, SizeType size) {
        List<Long> freeIds = slotMapper.selectList(SlotCandidateQuery.assignable(cabinetId, size))
                .stream().map(BizCompartment::getId).toList();
        preDeduction.replaceFreeSet(cabinetId, size, freeIds);
        log.log(System.Logger.Level.DEBUG, "空闲集合已同步 cabinetId={0} size={1} count={2}",
                cabinetId, size, freeIds.size());
    }

    @Override
    public void syncAllFreeSets(Long cabinetId) {
        for (SizeType size : SizeType.values()) {
            syncFreeSet(cabinetId, size);
        }
    }
}
