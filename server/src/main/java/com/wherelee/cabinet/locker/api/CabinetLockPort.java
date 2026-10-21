package com.wherelee.cabinet.locker.api;

import java.util.function.Supplier;

/**
 * 柜机级锁端口：只有 redisson 策略会真的加锁，其他策略下是直通。
 *
 * <p>为什么要暴露到端口：锁必须包住<b>整个用例（含事务）</b>，而用例入口在订单侧。
 * 锁的粒度与等待上限是 locker 的实现细节，调用方只需要“在这台柜机的锁内把这段做完”。
 */
public interface CabinetLockPort {

    /** 当前策略是否真的需要包锁。 */
    boolean active();

    <T> T aroundCabinet(String cabinetNo, Supplier<T> action);
}
