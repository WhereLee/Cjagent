package com.wherelee.cabinet.application.storage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.entity.BizCompartment;
import com.wherelee.cabinet.domain.enums.SlotStatus;
import com.wherelee.cabinet.domain.enums.SizeType;
import com.wherelee.cabinet.infrastructure.mapper.BizCompartmentMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * Redisson 策略的分配器：<b>本类不加锁</b>，锁在 {@link CabinetLockGuard} 里、在事务之外。
 *
 * <p>这是第 10 刀的实测结论：原先在这里加锁（事务内），并发用例仍然报"格口刚刚被占用"——
 * REPEATABLE READ 的快照在事务第一次读时就定下来了，锁挡不住旧快照的读。
 * 所以锁上移到用例入口，本类退化为"读候选 + 按偏好选一个 + 条件占用"，
 * 与 CAS 策略的差别只剩"外面有没有一把串行化的锁"。
 *
 * <p>仍然用条件更新而不是 {@code updateById}：即便锁失效（Redis 抖动、时钟、误用），
 * 正确性由 {@code WHERE status='FREE'} 与 {@code uk_order_active_slot} 保证。
 */
@Service
@ConditionalOnProperty(name = "cabinet.alloc.strategy", havingValue = "redisson")
public class RedissonSlotAllocator implements SlotAllocator {

    private final BizCompartmentMapper slotMapper;

    public RedissonSlotAllocator(BizCompartmentMapper slotMapper) {
        this.slotMapper = slotMapper;
    }

    @Override
    public AllocatedSlot allocate(Long cabinetId, SizeType required, Long orderId) {
        List<SizeType> acceptable = SizeType.acceptanceOrder(required);
        List<BizCompartment> candidates = slotMapper.selectList(Wrappers.<BizCompartment>lambdaQuery()
                .eq(BizCompartment::getCabinetId, cabinetId)
                .eq(BizCompartment::getStatus, SlotStatus.FREE)
                .in(BizCompartment::getSizeType, acceptable));
        if (candidates.isEmpty()) {
            throw new BizException(ResultCode.SLOT_UNAVAILABLE, "该柜机没有可用格口");
        }

        BizCompartment chosen = candidates.stream()
                .min(Comparator.comparingInt(s -> acceptable.indexOf(s.getSizeType())))
                .orElseThrow();

        if (slotMapper.reserveSlot(chosen.getId(), orderId) == 0) {
            // 有锁还被抢走 = 锁没起作用（配置或路由问题），要响出来而不是静默重试掩盖
            throw new BizException(ResultCode.SLOT_RACE_LOST, "格口刚刚被占用，请重试");
        }
        return new AllocatedSlot(chosen.getId(), chosen.getSizeType(), 1);
    }

    @Override
    public String strategy() {
        return "redisson";
    }
}
