package com.wherelee.cabinet.locker.infra;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.locker.api.CabinetSnapshot;
import com.wherelee.cabinet.locker.api.SizeType;
import com.wherelee.cabinet.locker.api.SiteSnapshot;
import com.wherelee.cabinet.locker.api.SlotQueryPort;
import com.wherelee.cabinet.locker.api.SlotSnapshot;
import com.wherelee.cabinet.locker.app.SlotCandidateQuery;
import com.wherelee.cabinet.locker.domain.BizCabinet;
import com.wherelee.cabinet.locker.domain.BizCompartment;
import com.wherelee.cabinet.locker.domain.BizSite;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 格口/柜机/点位的只读查询适配器：把“格口的表归格口读”这件事落成代码。
 *
 * <p>为什么要这一层：订单、工单、运营台以前各自 selectById 这些表，于是同一条
 * “可分配”判据在四处各写一遍。只读查询收到这里之后，调用方拿到的是快照，
 * 想自己拼状态也没有实体可以改。
 */
@Component
public class SlotQueryAdapter implements SlotQueryPort {

    private final BizCompartmentMapper slotMapper;
    private final BizCabinetMapper cabinetMapper;
    private final BizSiteMapper siteMapper;

    public SlotQueryAdapter(BizCompartmentMapper slotMapper, BizCabinetMapper cabinetMapper,
                            BizSiteMapper siteMapper) {
        this.slotMapper = slotMapper;
        this.cabinetMapper = cabinetMapper;
        this.siteMapper = siteMapper;
    }

    @Override
    public Optional<SlotSnapshot> slot(Long slotId) {
        if (slotId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(slotMapper.selectById(slotId)).map(SlotQueryAdapter::toSlot);
    }

    @Override
    public List<SlotSnapshot> slots(Collection<Long> slotIds) {
        if (slotIds == null || slotIds.isEmpty()) {
            return List.of();
        }
        return slotMapper.selectByIds(slotIds).stream().map(SlotQueryAdapter::toSlot).toList();
    }

    @Override
    public Optional<CabinetSnapshot> cabinet(Long cabinetId) {
        if (cabinetId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(cabinetMapper.selectById(cabinetId)).map(SlotQueryAdapter::toCabinet);
    }

    @Override
    public Optional<CabinetSnapshot> cabinetByNo(String cabinetNo) {
        if (cabinetNo == null || cabinetNo.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(cabinetMapper.selectOne(Wrappers.<BizCabinet>lambdaQuery()
                .eq(BizCabinet::getCabinetNo, cabinetNo).last("limit 1")))
                .map(SlotQueryAdapter::toCabinet);
    }

    @Override
    public Optional<SiteSnapshot> site(Long siteId) {
        if (siteId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(siteMapper.selectById(siteId)).map(SlotQueryAdapter::toSite);
    }

    @Override
    public List<SlotSnapshot> assignableSlots(Collection<SizeType> sizes) {
        if (sizes == null || sizes.isEmpty()) {
            return List.of();
        }
        return slotMapper.selectList(SlotCandidateQuery.assignable(sizes)).stream()
                .map(SlotQueryAdapter::toSlot).toList();
    }

    private static SlotSnapshot toSlot(BizCompartment slot) {
        return new SlotSnapshot(slot.getId(), slot.getTenantId(), slot.getCabinetId(), slot.getSlotNo(),
                nameOf(slot.getSizeType()), nameOf(slot.getStatus()), nameOf(slot.getAnomaly()),
                slot.getCurrentOrderId(), slot.getDoorOpenedBy(), nameOf(slot.getPresence()),
                slot.getDoorOpenAt(), slot.getDoorClosedAt());
    }

    private static CabinetSnapshot toCabinet(BizCabinet cabinet) {
        return new CabinetSnapshot(cabinet.getId(), cabinet.getTenantId(), cabinet.getSiteId(), cabinet.getCabinetNo(),
                cabinet.getName(), nameOf(cabinet.getCabinetStatus()), nameOf(cabinet.getOnlineState()));
    }

    private static SiteSnapshot toSite(BizSite site) {
        return new SiteSnapshot(site.getId(), site.getSiteCode(), site.getName(),
                site.getCategory(), site.getAddress());
    }

    private static String nameOf(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
