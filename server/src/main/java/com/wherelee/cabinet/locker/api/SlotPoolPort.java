package com.wherelee.cabinet.locker.api;

/**
 * 空闲格口池端口：prealloc 策略用来做准入的那份 Redis 集合，弹出、加回与重建都从这里走。
 *
 * <p>为什么不让调用方直接改集合：键怎么拼、按什么判据重建，只有 locker 知道；而这份集合
 * 同时被三个方向使用——分配时弹出、释放时加回、校准时按 DB 真相重建。三个方向各写一遍
 * 键规则，迟早出现"释放加回去了、分配却弹不到"。
 *
 * <p>集合只是<b>准入</b>，不是库存真相：真相在 {@code biz_compartment.status} 与
 * {@code uk_order_active_slot}。所以它允许滞后于 DB，但方向只能是<b>少卖</b>
 * （成员只能由 DB 真相写入，多卖在结构上做不到），滞后由 {@link #syncAllFreeSets} 收敛。
 *
 * <p>没有预占策略时（悲观锁 / CAS / Redisson）{@link #tryPop} 不会被调用；
 * {@link #addFree} 仍会被释放路径调用，那时它是空转——集合本来就没人在用。
 */
public interface SlotPoolPort {

    /**
     * 按“最小满足优先”从空闲集合里原子弹出一个格口。
     *
     * @return 弹到的格口；{@code null} 表示该柜机没有可用格口
     */
    Popped tryPop(Long cabinetId, SizeType required);

    /** 格口空出来了，回到可分配集合（SADD 天然幂等）。 */
    void addFree(Long cabinetId, SizeType sizeType, Long slotId);

    /** 用数据库真相重建某柜机某尺寸的空闲集合（校准入口）。 */
    void syncFreeSet(Long cabinetId, SizeType size);

    /** 某柜机全部尺寸一起校准：后台人工触发与 {@code FREE_SET_SYNC} 定时任务共用这一个入口。 */
    void syncAllFreeSets(Long cabinetId);

    /** 弹到的格口。尺寸可能比请求大（按偏好降级）。 */
    record Popped(SizeType size, Long slotId) {
    }
}
