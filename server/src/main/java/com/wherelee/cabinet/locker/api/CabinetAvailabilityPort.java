package com.wherelee.cabinet.locker.api;

import java.util.List;

/**
 * 用户端柜机可用性入口：某个柜机各尺寸的空闲数、可代关的格口，以及“帮忙关上了”的确认。
 *
 * <p>为什么放在 api：这三件事都以 {@code biz_compartment} 的现状为准（门是否关、异常是否解除），
 * 规则在柜机服务里；BFF 只负责把柜机编号与登录态传进来。
 */
public interface CabinetAvailabilityPort {

    /** 可代关的格口（门开着、有主、当前用户可见的那一扇）。 */
    record ClosableView(String cabinetNo, String slotNo, String sizeType) {
    }

    /** 某尺寸的空闲数。 */
    record SizeFree(String sizeType, long free) {
    }

    /** 柜机可用性。 */
    record Availability(String cabinetNo, List<SizeFree> sizes) {
    }

    List<ClosableView> closableSlots(String cabinetNo);

    String confirmClosedByAnother(String cabinetNo, String slotNo, Long helperCustomerId);

    Availability of(String cabinetNo);
}
