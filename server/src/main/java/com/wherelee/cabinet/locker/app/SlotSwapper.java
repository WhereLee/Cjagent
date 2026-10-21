package com.wherelee.cabinet.locker.app;

import com.wherelee.cabinet.locker.api.SizeType;
import com.wherelee.cabinet.locker.api.SlotPort;
import com.wherelee.cabinet.locker.api.SlotQueryPort;
import com.wherelee.cabinet.locker.api.SlotSnapshot;
import com.wherelee.cabinet.locker.api.SlotSwapPort;
import com.wherelee.cabinet.locker.domain.BizCompartment;
import com.wherelee.cabinet.locker.infra.BizCompartmentMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** {@link SlotSwapPort} 的进程内实现：候选、预占、摘旧格三步都在 locker 内完成。 */
@Component
public class SlotSwapper implements SlotSwapPort {

    private final BizCompartmentMapper slotMapper;
    private final SlotPort slots;
    private final SlotQueryPort slotQueries;

    public SlotSwapper(BizCompartmentMapper slotMapper, SlotPort slots, SlotQueryPort slotQueries) {
        this.slotMapper = slotMapper;
        this.slots = slots;
        this.slotQueries = slotQueries;
    }

    @Override
    public Optional<SlotSnapshot> swapIn(Long cabinetId, SizeType required, Long orderId, Long oldSlotId) {
        List<BizCompartment> candidates = slotMapper.selectList(
                SlotCandidateQuery.assignable(cabinetId, List.of(required)));
        for (BizCompartment slot : candidates) {
            if (slot.getId().equals(oldSlotId)) {
                continue;
            }
            if (slotMapper.reserveSlot(slot.getId(), orderId) == 0) {
                continue;
            }
            // 先抢新格再摘旧格：两人同时换也不会换到同一个；抢不到就试下一个
            slots.markFaulty(oldSlotId);
            return slotQueries.slot(slot.getId());
        }
        return Optional.empty();
    }
}
