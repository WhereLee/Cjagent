package com.wherelee.cabinet.locker.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SlotQueryPort {

    Optional<SlotSnapshot> slot(Long slotId);

    List<SlotSnapshot> slots(Collection<Long> slotIds);

    Optional<CabinetSnapshot> cabinet(Long cabinetId);

    Optional<CabinetSnapshot> cabinetByNo(String cabinetNo);

    List<CabinetSnapshot> cabinets(Collection<Long> cabinetIds);

    List<CabinetSnapshot> cabinets();

    List<CabinetSnapshot> cabinetsOfSite(Long siteId);

    Optional<SiteSnapshot> site(Long siteId);

    Optional<SiteSnapshot> siteByCode(String siteCode);

    List<SiteSnapshot> sites();

    List<SlotSnapshot> assignableSlots(Collection<SizeType> sizes);

    List<SlotSnapshot> assignableSlots();
}
