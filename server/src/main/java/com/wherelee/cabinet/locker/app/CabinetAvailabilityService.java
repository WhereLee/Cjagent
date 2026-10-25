package com.wherelee.cabinet.locker.app;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.locker.domain.BizCabinet;
import com.wherelee.cabinet.locker.domain.BizCompartment;
import com.wherelee.cabinet.locker.api.CabinetAvailabilityPort;
import com.wherelee.cabinet.locker.api.SizeType;
import com.wherelee.cabinet.locker.infra.BizCabinetMapper;
import com.wherelee.cabinet.locker.infra.BizCompartmentMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class CabinetAvailabilityService implements com.wherelee.cabinet.locker.api.CabinetAvailabilityPort {

    private final BizCabinetMapper cabinetMapper;
    private final BizCompartmentMapper slotMapper;
    private final CompartmentStateService states;

    public CabinetAvailabilityService(BizCabinetMapper cabinetMapper, BizCompartmentMapper slotMapper,
                                      CompartmentStateService states) {
        this.cabinetMapper = cabinetMapper;
        this.slotMapper = slotMapper;
        this.states = states;
    }

    public List<ClosableView> closableSlots(String cabinetNo) {
        BizCabinet cabinet = requireCabinet(cabinetNo);
        return states.closableSlots(cabinet.getId()).stream()
                .map(slot -> new ClosableView(cabinet.getCabinetNo(), slot.slotNo(), slot.sizeType()))
                .toList();
    }

    public String confirmClosedByAnother(String cabinetNo, String slotNo, Long helperCustomerId) {
        BizCabinet cabinet = requireCabinet(cabinetNo);
        BizCompartment slot = slotMapper.selectOne(Wrappers.<BizCompartment>lambdaQuery()
                .eq(BizCompartment::getCabinetId, cabinet.getId())
                .eq(BizCompartment::getSlotNo, slotNo));
        if (slot == null) {
            throw new BizException(ResultCode.DATA_NOT_FOUND, "这台暂存柜上没有这个格口：" + slotNo);
        }
        return states.confirmClosedByAnother(slot.getId(), helperCustomerId);
    }

    private BizCabinet requireCabinet(String cabinetNo) {
        BizCabinet cabinet = cabinetMapper.selectOne(Wrappers.<BizCabinet>lambdaQuery()
                .eq(BizCabinet::getCabinetNo, cabinetNo));
        if (cabinet == null) {
            throw new BizException(ResultCode.DATA_NOT_FOUND, "暂存柜不存在：" + cabinetNo);
        }
        return cabinet;
    }

    public Availability of(String cabinetNo) {
        BizCabinet cabinet = cabinetMapper.selectOne(Wrappers.<BizCabinet>lambdaQuery()
                .eq(BizCabinet::getCabinetNo, cabinetNo));
        if (cabinet == null) {
            throw new BizException(ResultCode.DATA_NOT_FOUND, "暂存柜不存在：" + cabinetNo);
        }
        List<SizeFree> sizes = new ArrayList<>(SizeType.values().length);
        for (SizeType size : SizeType.values()) {
            sizes.add(new SizeFree(size.name(),
                    slotMapper.selectCount(SlotCandidateQuery.assignable(cabinet.getId(), size))));
        }
        return new Availability(cabinet.getCabinetNo(), List.copyOf(sizes));
    }
}
