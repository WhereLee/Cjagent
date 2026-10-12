package com.wherelee.cabinet.application.storage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.entity.BizCabinet;
import com.wherelee.cabinet.domain.entity.BizCompartment;
import com.wherelee.cabinet.domain.enums.SizeType;
import com.wherelee.cabinet.infrastructure.mapper.BizCabinetMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizCompartmentMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 一台暂存柜各尺寸还剩多少<b>正常可分配</b>的格口（第 15 刀只读接口的第一层）。
 *
 * <p>为什么要有这个类，而不是在控制器里直接 count：屏上写的数与下单时能不能分到格，
 * 必须是<b>同一个问题的同一个答案</b>。所以这里不写自己的 SQL，只调
 * {@link SlotCandidateQuery#assignable}——四个分配器、预扣集合、定时校准用的都是它。
 * 两处各写一套判据迟早会变成“屏幕说还有 3 个小格、一点下单就说没格”，
 * 或者反过来“屏幕说 0、其实还能卖”，后一种等于把能卖的格子说成不能卖。
 *
 * <p>口径（用户 2026-10-12 明确）：异常格口不参与售卖，所以它们**不计入这里的数字**；
 * 一台柜机的格口全部异常时，这里三个尺寸都是 0，用户在屏幕上看到的就不是“还能去”。
 */
@Service
public class CabinetAvailabilityService {

    private final BizCabinetMapper cabinetMapper;
    private final BizCompartmentMapper slotMapper;
    private final CompartmentStateService states;

    public CabinetAvailabilityService(BizCabinetMapper cabinetMapper, BizCompartmentMapper slotMapper,
                                      CompartmentStateService states) {
        this.cabinetMapper = cabinetMapper;
        this.slotMapper = slotMapper;
        this.states = states;
    }

    /**
     * 可代关清单（按柜机编号）。<b>对外一律用编号，不泄内部 ID</b>：
     * 内部 ID 一旦落到客户端，就会有人拿它去猜别的接口入参（这条约定本接口自己先违反了）。
     */
    public record ClosableView(String cabinetNo, String slotNo, String sizeType) {
    }

    public List<ClosableView> closableSlots(String cabinetNo) {
        BizCabinet cabinet = requireCabinet(cabinetNo);
        return states.closableSlots(cabinet.getId()).stream()
                .map(slot -> new ClosableView(cabinet.getCabinetNo(), slot.slotNo(), slot.sizeType()))
                .toList();
    }

    /** 另一位用户反馈“我帮他关上了”：按编号定位到格口再走业务判断。 */
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

    /** 一个尺寸的剩余正常格口数。 */
    public record SizeFree(String sizeType, long free) {
    }

    /** 一台暂存柜的剩余情况：只给编号，不给内部 ID（接口层不回实体）。 */
    public record Availability(String cabinetNo, List<SizeFree> sizes) {
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
