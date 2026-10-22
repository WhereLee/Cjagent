package com.wherelee.cabinet.locker.app;

import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.locker.api.SizeType;
import com.wherelee.cabinet.locker.api.SlotAllocator;
import com.wherelee.cabinet.locker.api.SlotAllocator.AllocatedSlot;
import com.wherelee.cabinet.locker.api.SlotHoldPort;
import com.wherelee.cabinet.locker.infra.BizCompartmentMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Redis 预占分配：先从空闲集合里原子弹出一个格口，再用条件更新把它绑到订单上。
 *
 * <p>它要解决的是第 9 刀量出的瓶颈——"连接池 10 × 事务 1.6s"：热点柜机上所有请求都去撞
 * 同一批候选行，谁抢到要等 DB 行锁给出答案。预占把这个问题前移到 Redis 的单线程原子性里，
 * 等 DB 被问到时答案已经定了，那一次条件更新几乎不会失败。
 *
 * <p><b>与其他三个策略的差别是它写了一份事务外的状态</b>，所以必须自带收敛路径
 * （这是 {@link SlotAllocator} 契约 ② 对它的要求）：
 * <ul>
 *   <li>hold 标记带 TTL（{@code cabinet.alloc.hold-ttl}）：预占之后、订单事务提交之前进程崩溃，
 *       标记到期自动消失；</li>
 *   <li>{@code FREE_SET_SYNC} 定时按 DB 真相重建空闲集合：悬空的格口下一轮回到可卖状态。</li>
 * </ul>
 * 两条的方向都是<b>少卖</b>而不是超卖：集合成员只能由 DB 真相写入，多卖在结构上做不到。
 *
 * <p>少卖会在两种时候发生，都是设计内的：① 预占成功但订单事务后来回滚了（例如预收扣款
 * 余额不足）——DB 侧格口回到 FREE，Redis 侧它已不在集合里，等校准补回；
 * ② 集合滞后于 DB（有人刚取消、校准还没跑）。正常取消路径不受影响：
 * {@code OrderReleaseService} 释放格口时会调 {@code SlotPoolPort.addFree} 把它放回集合。
 *
 * <p>为什么预占之后还要 {@code reserveSlot}：Redis 集合是准入优化，不是库存真相。
 * 最终裁判仍然是 DB 的条件更新与 {@code uk_order_active_slot}。
 */
@Service
@ConditionalOnProperty(name = "cabinet.alloc.strategy", havingValue = "prealloc")
public class PreallocSlotAllocator implements SlotAllocator {

    private final SlotHoldPort slotHold;
    private final BizCompartmentMapper slotMapper;

    /** 预占租约：要覆盖"预占 → 订单事务提交"这一段。进程崩在中间时，到期即释放。 */
    @Value("${cabinet.alloc.hold-ttl:180s}")
    private Duration holdTtl;

    public PreallocSlotAllocator(SlotHoldPort slotHold, BizCompartmentMapper slotMapper) {
        this.slotHold = slotHold;
        this.slotMapper = slotMapper;
    }

    @Override
    public AllocatedSlot allocate(Long cabinetId, SizeType required, Long orderId) {
        List<SizeType> acceptable = SizeType.acceptanceOrder(required);
        // 占位标记的键用订单主键：它只要求"同一逻辑操作唯一"。
        // 订单号的拼法（"SO" + id）是订单侧的规则，在这里再复制一遍就会出现两个真相
        String holdKey = String.valueOf(orderId);

        SlotHoldPort.Hold hold = slotHold.tryHold(cabinetId, required, holdKey, holdTtl);
        if (hold == null) {
            // 集合空了。它可能滞后于 DB（有人刚取消、校准还没跑），但按"缺货"回是安全的：
            // 集合只会比 DB 少不会比 DB 多，所以这里说没位时 DB 里也真的没有可卖的位
            throw new BizException(ResultCode.SLOT_UNAVAILABLE, "该柜机没有可用格口");
        }

        if (slotMapper.reserveSlot(hold.slotId(), orderId) != 1) {
            // 集合说它空闲、DB 说它不空闲：Redis 与 DB 短暂不一致（设计内状态）。
            // 用 commit 而不是 release——格口在 DB 里确实不可分配，放回集合只会让
            // 下一个请求也白跑一趟；等校准按 DB 真相重建集合。
            slotHold.commit(hold, holdKey);
            throw new BizException(ResultCode.SLOT_RACE_LOST, "格口刚刚被占用，请重试");
        }

        // 落定：删掉 hold 标记。格口不回集合——它已经在 DB 里绑给这张单了。
        // 注意这一步在调用方事务内：事务若回滚，Redis 这边不会跟着回滚，
        // 结果就是上面类注释里说的少卖，由 FREE_SET_SYNC 收敛。
        slotHold.commit(hold, holdKey);

        // 预占是一次原子弹出，没有 CAS 那样的重试次数；但"降级到第几档"要如实带出去，
        // 它等于命中尺寸在偏好序里的下标（集合是按尺寸分开建的，试了几个集合就是第几档）
        return new AllocatedSlot(hold.slotId(), hold.size(), acceptable.indexOf(hold.size()) + 1);
    }

    @Override
    public String strategy() {
        return "prealloc";
    }
}
