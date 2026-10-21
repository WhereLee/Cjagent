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

    List<CabinetSnapshot> cabinets(Collection<Long> cabinetIds);

    /** 全部柜机（点位余量聚合用，避免按点位逐台查库）。 */
    List<CabinetSnapshot> cabinets();

    /** 某点位下的柜机（按柜机编号升序）。 */
    List<CabinetSnapshot> cabinetsOfSite(Long siteId);

    Optional<SiteSnapshot> site(Long siteId);

    Optional<SiteSnapshot> siteByCode(String siteCode);

    /** 全部点位（按点位编码升序）。 */
    List<SiteSnapshot> sites();

    /** 可分配的格口（按尺寸；供点位余量统计用）。 */
    List<SlotSnapshot> assignableSlots(Collection<SizeType> sizes);

    /** 全部尺寸的可分配格口（点位余量聚合用）。 */
    List<SlotSnapshot> assignableSlots();
}
