package com.wherelee.cabinet.locker.api;

import java.time.Duration;

/**
 * 预占端口：prealloc 策略下“先从 Redis 空闲集合原子弹出一个格口、再在 DB 里绑单”的那套占位动作。
 *
 * <p>为什么必须在 locker：占位标记、空闲集合与归还用哪条 key，全是预占策略的运行时结构；
 * 分配器只需要说“帮我占一个”“落定了 / 不认领了”。
 *
 * <p>没有预占策略时（悲观锁 / CAS / Redisson）这些方法不会被调用。
 *
 * <p>{@code holdKey} 是占位标记的键，只要求<b>同一逻辑操作唯一</b>——
 * 预占分配器用订单主键就够了，它不该知道订单号是怎么拼出来的（那是订单侧的规则，
 * 在这里再复制一遍就会出现两个真相）。
 */
public interface SlotHoldPort {

    /**
     * 按“最小满足优先”预占一个格口。
     *
     * @return 预占句柄；返回 {@code null} 表示该柜机没有可用格口
     */
    Hold tryHold(Long cabinetId, SizeType required, String holdKey, Duration ttl);

    /** 不认领这个格口：删标记并把它放回空闲集合（幂等）。 */
    void release(Hold hold, String holdKey);

    /** 落定：只删标记，格口不回空闲集合——它已经在 DB 里绑给订单了。 */
    void commit(Hold hold, String holdKey);

    /** 用数据库真相重建某柜机某尺寸的空闲集合（校准入口）。 */
    void syncFreeSet(Long cabinetId, SizeType size);

    /** 某柜机全部尺寸一起校准：后台人工触发与 {@code FREE_SET_SYNC} 定时任务共用这一个入口。 */
    void syncAllFreeSets(Long cabinetId);

    /** 预占句柄：归还时必须放回**同一个**集合。 */
    record Hold(Long cabinetId, SizeType size, Long slotId, String freeKey) {
    }
}
