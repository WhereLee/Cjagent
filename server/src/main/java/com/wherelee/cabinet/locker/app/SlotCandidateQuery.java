package com.wherelee.cabinet.locker.app;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.locker.domain.BizCompartment;
import com.wherelee.cabinet.locker.api.SlotStatus;
import com.wherelee.cabinet.locker.api.SizeType;

import java.util.Collection;

public final class SlotCandidateQuery {

    private SlotCandidateQuery() {
    }

    public static LambdaQueryWrapper<BizCompartment> assignable(Long cabinetId, Collection<SizeType> acceptable) {
        return assignableBase(cabinetId).in(BizCompartment::getSizeType, acceptable);
    }

    public static LambdaQueryWrapper<BizCompartment> assignable(Long cabinetId, SizeType size) {
        return assignableBase(cabinetId).eq(BizCompartment::getSizeType, size);
    }

    public static LambdaQueryWrapper<BizCompartment> assignable(Collection<SizeType> acceptable) {
        return assignableBase(null).in(BizCompartment::getSizeType, acceptable);
    }

    private static LambdaQueryWrapper<BizCompartment> assignableBase(Long cabinetId) {
        return Wrappers.<BizCompartment>lambdaQuery()
                .eq(cabinetId != null, BizCompartment::getCabinetId, cabinetId)
                .eq(BizCompartment::getStatus, SlotStatus.FREE)
                .isNull(BizCompartment::getDoorOpenAt)
                .isNull(BizCompartment::getAnomaly);
    }
}
