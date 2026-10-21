package com.wherelee.cabinet.locker.infra;

import com.wherelee.cabinet.locker.api.SlotPoolPort;
import com.wherelee.cabinet.locker.api.SizeType;
import org.springframework.stereotype.Component;

/** {@link SlotPoolPort} 的进程内实现：写回预扣集合（集合本身由 locker 维护）。 */
@Component
public class SlotPoolAdapter implements SlotPoolPort {

    private final SlotPreDeductionService preDeduction;

    public SlotPoolAdapter(SlotPreDeductionService preDeduction) {
        this.preDeduction = preDeduction;
    }

    @Override
    public void addFree(Long cabinetId, SizeType sizeType, Long slotId) {
        preDeduction.addFree(cabinetId, sizeType, slotId);
    }
}
