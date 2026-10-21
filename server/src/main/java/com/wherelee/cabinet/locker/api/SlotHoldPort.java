package com.wherelee.cabinet.locker.api;

import java.time.Duration;

/**
 * 预扣端口：预扣策略下“先占住格口、再异步落库”的那套占位动作。
 *
 * <p>为什么必须在 locker：占位标记、空闲集合与归还用哪条 key，全是预扣策略的运行时结构；
 * 订单侧只需要说“帮我占一个”“落库了/退了”与“这单还占着吗”。
 *
 * <p>没有预扣策略时（悲观锁/CAS/Redisson）这些方法不会被调用。
 */
public interface SlotHoldPort {

    /**
     * 按“最小满足优先”预扣一个格口。
     *
     * @return 预扣句柄；返回 {@code null} 表示该柜机没有可用格口
     */
    Hold tryHold(Long cabinetId, SizeType required, String orderNo, Duration ttl, Long customerId);

    /** 发送失败：归还占位并把格口放回空闲集合（幂等）。 */
    void release(Hold hold, String orderNo);

    /** 取消“已预扣未落库”的单：先校验归属再归还；没有占位标记返回 false。 */
    boolean releaseQueued(String orderNo, Long customerId);

    /** 落库前确认占位还在（不在 = 已被取消或已过期）。 */
    boolean stillHeld(String orderNo);

    /** 落库成功后收尾：只删标记，格口不回空闲集合。 */
    void commit(Hold hold, String orderNo);

    /** 消费者手上只有“柜机 + 尺寸 + 格口”时重建收尾上下文（集合 key 规则只在 locker 内）。 */
    Hold rehydrate(Long cabinetId, SizeType size, Long slotId);

    /** 用数据库真相重建某柜机某尺寸的空闲集合（同步/校准入口）。 */
    void syncFreeSet(Long cabinetId, SizeType size);

    /** 预扣句柄：归还时必须放回**同一个**集合。 */
    record Hold(Long cabinetId, SizeType size, Long slotId, String freeKey) {
    }
}
