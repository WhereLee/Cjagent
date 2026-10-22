package com.wherelee.cabinet.locker.infra;

import com.wherelee.cabinet.locker.api.SizeType;
import com.wherelee.cabinet.locker.api.SlotHoldPort;
import com.wherelee.cabinet.locker.app.SlotCandidateQuery;
import com.wherelee.cabinet.locker.domain.BizCompartment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/** {@link SlotHoldPort} 的进程内实现：预占、落定与集合同步都在 locker 内完成。 */
@Component
public class SlotHoldAdapter implements SlotHoldPort {

    private static final System.Logger log = System.getLogger(SlotHoldAdapter.class.getName());

    private final SlotPreDeductionService preDeduction;
    private final BizCompartmentMapper slotMapper;

    public SlotHoldAdapter(SlotPreDeductionService preDeduction, BizCompartmentMapper slotMapper) {
        this.preDeduction = preDeduction;
        this.slotMapper = slotMapper;
    }

    @Override
    public Hold tryHold(Long cabinetId, SizeType required, String holdKey, Duration ttl) {
        SlotPreDeductionService.PreDeduct deducted = preDeduction.tryPreDeduct(
                cabinetId, SizeType.acceptanceOrder(required), holdKey, ttl);
        return deducted == null ? null
                : new Hold(cabinetId, deducted.size(), deducted.slotId(), deducted.freeKey());
    }

    @Override
    public void release(Hold hold, String holdKey) {
        preDeduction.release(toPreDeduct(hold), holdKey);
    }

    @Override
    public void commit(Hold hold, String holdKey) {
        preDeduction.commit(toPreDeduct(hold), holdKey);
    }

    @Override
    public void syncFreeSet(Long cabinetId, SizeType size) {
        // 必须用与分配同一条“可分配”判据：校准若把“门开着 / 有遗留物”的格口写回集合，
        // 就是把 DB 已经拦住了的事又放回 Redis 里放行
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

    private static SlotPreDeductionService.PreDeduct toPreDeduct(Hold hold) {
        return new SlotPreDeductionService.PreDeduct(hold.size(), hold.slotId(), hold.freeKey());
    }
}
