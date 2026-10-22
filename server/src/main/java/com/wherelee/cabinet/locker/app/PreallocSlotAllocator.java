package com.wherelee.cabinet.locker.app;

import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.locker.api.SizeType;
import com.wherelee.cabinet.locker.api.SlotAllocator;
import com.wherelee.cabinet.locker.api.SlotAllocator.AllocatedSlot;
import com.wherelee.cabinet.locker.api.SlotPoolPort;
import com.wherelee.cabinet.locker.infra.BizCompartmentMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Redis 预占分配：先从空闲集合里原子弹出一个格口，再用条件更新把它绑到订单上。
 *
 * <p>它要解决的是第 9 刀量出的瓶颈——"连接池 10 × 事务 1.6s"：热点柜机上所有请求都去撞
 * 同一批候选行，谁抢到要等 DB 行锁给出答案。预占把这个问题前移到 Redis 的单线程原子性里，
 * 等 DB 被问到时答案已经定了，那一次条件更新几乎不会失败。
 *
 * <p><b>与其他三个策略的差别是它写了一份事务外的状态</b>：从集合里弹出这个动作不会随订单
 * 事务回滚。所以它必须自带收敛路径（{@link SlotAllocator} 契约 ②）——{@code FREE_SET_SYNC}
 * 定时按 DB 真相重建集合，悬空的格口下一轮就回到可卖状态。方向是<b>少卖</b>而不是超卖：
 * 集合成员只能由 DB 真相写入，多卖在结构上做不到。
 *
 * <p>少卖会在两种时候发生，都是设计内的：① 弹出成功但订单事务后来回滚了（例如预收扣款
 * 余额不足）——DB 侧格口回到 FREE，Redis 侧它已不在集合里，等校准补回；② 集合滞后于 DB
 * （有人刚取消、校准还没跑）。正常释放路径不受影响：{@code OrderReleaseService} 让格口
 * 空出来时会调 {@code SlotPoolPort.addFree} 把它放回集合。
 *
 * <p>为什么弹出之后还要 {@code reserveSlot}：Redis 集合是准入优化，不是库存真相。
 * 最终裁判仍然是 DB 的条件更新与 {@code uk_order_active_slot}。
 *
 * <p>这里曾经还配一套"占位标记 + 租约 TTL"，那是异步落库时代的东西（预占与落库隔着一次
 * MQ 往返，需要标记说明这个位正在被谁处理）。现在两步在同一个方法、同一个事务里，
 * 中间没有窗口，标记没有保护对象，已删——理由记在 {@code SlotPreDeductionService} 类注释里。
 */
@Service
@ConditionalOnProperty(name = "cabinet.alloc.strategy", havingValue = "prealloc")
public class PreallocSlotAllocator implements SlotAllocator {

    private final SlotPoolPort slotPool;
    private final BizCompartmentMapper slotMapper;

    public PreallocSlotAllocator(SlotPoolPort slotPool, BizCompartmentMapper slotMapper) {
        this.slotPool = slotPool;
        this.slotMapper = slotMapper;
    }

    @Override
    public AllocatedSlot allocate(Long cabinetId, SizeType required, Long orderId) {
        List<SizeType> acceptable = SizeType.acceptanceOrder(required);

        SlotPoolPort.Popped popped = slotPool.tryPop(cabinetId, required);
        if (popped == null) {
            // 集合空了。它可能滞后于 DB（有人刚取消、校准还没跑），但按"缺货"回是安全的：
            // 集合只会比 DB 少不会比 DB 多，所以这里说没位时 DB 里也真没有可卖的位
            throw new BizException(ResultCode.SLOT_UNAVAILABLE, "该柜机没有可用格口");
        }

        if (slotMapper.reserveSlot(popped.slotId(), orderId) != 1) {
            // 集合说它空闲、DB 说它不空闲：Redis 与 DB 短暂不一致（设计内状态）。
            // 故意不放回集合——它在 DB 里确实不可分配，放回去只会让下一个请求也白跑一趟；
            // 等校准按 DB 真相重建。这里抛异常，调用方事务回滚，订单侧不留痕迹。
            throw new BizException(ResultCode.SLOT_RACE_LOST, "格口刚刚被占用，请重试");
        }

        // 预占是一次原子弹出，没有 CAS 那样的重试次数；但"降级到第几档"要如实带出去，
        // 它等于命中尺寸在偏好序里的下标（集合按尺寸分开建，试到第几个集合就是第几档）
        return new AllocatedSlot(popped.slotId(), popped.size(), acceptable.indexOf(popped.size()) + 1);
    }

    @Override
    public String strategy() {
        return "prealloc";
    }
}
