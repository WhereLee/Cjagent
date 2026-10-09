package com.wherelee.cabinet.application.storage;

import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.entity.BizCompartment;
import com.wherelee.cabinet.domain.enums.SizeType;
import com.wherelee.cabinet.infrastructure.mapper.BizCompartmentMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * 乐观 CAS 分配：不加任何锁，读候选后按偏好顺序逐个尝试条件更新，谁先把
 * {@code FREE → RESERVED} 写成功谁得到。
 *
 * <p>与悲观锁的取舍是本项目最该被问到的一组对比：
 * <ul>
 *   <li>吞吐：CAS 不串行化，热点柜机上明显更高；但冲突会转成重试，
 *       <b>高竞争下重试风暴反而更烧 CPU 和连接</b>；</li>
 *   <li>锁副作用：CAS 不产生 next-key lock，不会阻塞"往同一柜机加格口"这类无关写；</li>
 *   <li>失败语义：CAS 能明确区分"真没位"与"抢输了"（10409 / 10410），
 *       前者重试无用，后者重试有机会——混合流量下这个区别直接决定用户体验。</li>
 * </ul>
 *
 * <p>{@code maxAttempts} 是必要的止损：候选最多 24 个，但极端竞争下"全扫一遍"会把一次
 * 请求变成 24 次 UPDATE。宁可早点告诉用户"稍后再试"，也不要占着数据库连接空转。
 */
@Service
@ConditionalOnProperty(name = "cabinet.alloc.strategy", havingValue = "cas")
public class CasSlotAllocator implements SlotAllocator {

    /** 单次请求最多尝试抢占的候选数（止损，见类注释；测试要引用它，故公开）。 */
    public static final int MAX_ATTEMPTS = 6;

    private final BizCompartmentMapper slotMapper;

    public CasSlotAllocator(BizCompartmentMapper slotMapper) {
        this.slotMapper = slotMapper;
    }

    @Override
    public AllocatedSlot allocate(Long cabinetId, SizeType required, Long orderId) {
        List<SizeType> acceptable = SizeType.acceptanceOrder(required);

        // 候选定义共用 SlotCandidateQuery（四个分配器 + 预扣集合 + 校准同一条判据）
        List<BizCompartment> candidates = slotMapper.selectList(
                SlotCandidateQuery.assignable(cabinetId, acceptable));

        if (candidates.isEmpty()) {
            throw new BizException(ResultCode.SLOT_UNAVAILABLE, "该柜机没有可用格口");
        }

        List<BizCompartment> ordered = candidates.stream()
                .sorted(Comparator.comparingInt(s -> acceptable.indexOf(s.getSizeType())))
                .toList();

        int tried = 0;
        for (BizCompartment candidate : ordered) {
            if (tried >= MAX_ATTEMPTS) {
                break;
            }
            tried++;
            if (slotMapper.reserveSlot(candidate.getId(), orderId) == 1) {
                return new AllocatedSlot(candidate.getId(), candidate.getSizeType(), tried);
            }
        }

        // 一轮都没抢到：还有可分配位就是竞争失败（可重试），真一个不剩才是缺货。
        // 这里的“可用”故意用同一条判据：“有位但门开着/有遗留物”算缺货而不是“抢输了”——
        // 告诉用户重试有机会，而他重试一万次也赢不了，比告诉他没位更坑
        long stillFree = slotMapper.selectCount(SlotCandidateQuery.assignable(cabinetId, acceptable));
        throw new BizException(stillFree > 0 ? ResultCode.SLOT_RACE_LOST : ResultCode.SLOT_UNAVAILABLE,
                stillFree > 0 ? "格口刚刚被占用，请重试" : "该柜机没有可用格口");
    }

    @Override
    public String strategy() {
        return "cas";
    }
}
