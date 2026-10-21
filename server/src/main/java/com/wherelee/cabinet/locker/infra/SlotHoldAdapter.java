package com.wherelee.cabinet.locker.infra;

import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.locker.api.SizeType;
import com.wherelee.cabinet.locker.api.SlotHoldPort;
import com.wherelee.cabinet.locker.app.SlotCandidateQuery;
import com.wherelee.cabinet.locker.domain.BizCompartment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** {@link SlotHoldPort} 的进程内实现：预扣、归还与集合同步都在 locker 内完成。 */
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
    public Hold tryHold(Long cabinetId, SizeType required, String orderNo, Duration ttl, Long customerId) {
        SlotPreDeductionService.PreDeduct deducted = preDeduction.tryPreDeduct(
                cabinetId, SizeType.acceptanceOrder(required), orderNo, ttl, customerId);
        return deducted == null ? null
                : new Hold(cabinetId, deducted.size(), deducted.slotId(), deducted.freeKey());
    }

    @Override
    public void release(Hold hold, String orderNo) {
        preDeduction.release(toPreDeduct(hold), orderNo);
    }

    @Override
    public boolean releaseQueued(String orderNo, Long customerId) {
        Map<String, String> hold = preDeduction.holdOf(orderNo);
        if (hold.isEmpty()) {
            return false;
        }
        if (!String.valueOf(customerId).equals(hold.get("cust"))) {
            // 不区分“不是你的单”与“单不存在”，避免用返回差值枚举他人单号
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "寄存单不存在");
        }
        return preDeduction.releaseByKey(hold.get("key"), orderNo, Long.valueOf(hold.get("slot")));
    }

    @Override
    public boolean stillHeld(String orderNo) {
        return preDeduction.hasHold(orderNo);
    }

    @Override
    public void commit(Hold hold, String orderNo) {
        preDeduction.commit(toPreDeduct(hold), orderNo);
    }

    @Override
    public Hold rehydrate(Long cabinetId, SizeType size, Long slotId) {
        SlotPreDeductionService.PreDeduct deducted = preDeduction.rehydrate(cabinetId, size, slotId);
        return new Hold(cabinetId, deducted.size(), deducted.slotId(), deducted.freeKey());
    }

    @Override
    public void syncFreeSet(Long cabinetId, SizeType size) {
        List<Long> freeIds = slotMapper.selectList(SlotCandidateQuery.assignable(cabinetId, size))
                .stream().map(BizCompartment::getId).toList();
        preDeduction.replaceFreeSet(cabinetId, size, freeIds);
        log.log(System.Logger.Level.DEBUG, "空闲集合已同步 cabinetId={0} size={1} count={2}",
                cabinetId, size, freeIds.size());
    }

    private static SlotPreDeductionService.PreDeduct toPreDeduct(Hold hold) {
        return new SlotPreDeductionService.PreDeduct(hold.size(), hold.slotId(), hold.freeKey());
    }
}
