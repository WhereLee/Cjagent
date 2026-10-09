package com.wherelee.cabinet.domain.enums;

import java.util.Locale;

/**
 * 柜内物检结论。
 *
 * <p><b>三值而不是布尔，是这个模型里最要紧的一个决定。</b>真实设备会说"不知道"
 * （柜机离线、传感器脏污、红外被反光欺骗），把它折成 true/false 就是在最需要谨慎的地方造假：
 * 折成"没东西"会把别人的行李卖给下一位，折成"有东西"会把一个空柜永久锁死。
 * 两种错的代价都不是一句"默认取保守值"能承担的。
 *
 * <p>{@link #UNKNOWN} 的处置只有一条：<b>当成"没有凭据"</b>——格口转
 * {@link CompartmentAnomaly#CONTENT_UNVERIFIED}，不可分配，等一次人来确认。
 * 它既不放行也不定罪，只是把"我不知道"如实记下来。
 */
public enum Presence {

    /** 检测到物品 */
    PRESENT,
    /** 确认无物 */
    ABSENT,
    /** 测不到 / 设备不知道 */
    UNKNOWN;

    /** 能不能据此判定"这个格口可以卖给别人"。只有明确无物才算。 */
    public boolean confirmsEmpty() {
        return this == ABSENT;
    }

    public static Presence of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
