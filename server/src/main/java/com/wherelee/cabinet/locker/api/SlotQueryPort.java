package com.wherelee.cabinet.locker.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** 格口/柜机/点位的只读查询端口。 */
public interface SlotQueryPort {

    Optional<SlotSnapshot> slot(Long slotId);

    List<SlotSnapshot> slots(Collection<Long> slotIds);

    Optional<CabinetSnapshot> cabinet(Long cabinetId);

    Optional<CabinetSnapshot> cabinetByNo(String cabinetNo);

    Optional<SiteSnapshot> site(Long siteId);

    /** 可分配的格口（供点位余量与分配候选使用）。 */
    List<SlotSnapshot> assignableSlots(Collection<SizeType> sizes);
}
