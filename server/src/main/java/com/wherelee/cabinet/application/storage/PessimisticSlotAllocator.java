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
 * 悲观锁分配：先 {@code FOR UPDATE} 把候选行锁住，再在锁内挑一个占用。
 *
 * <p><b>它的正确性最好讲，代价也最明确</b>：同一柜机的空闲格口会被串行化，
 * 热点柜机（大格口只剩 2 个）在高并发下吞吐直接落到"一次一个请求"。
 * 更隐蔽的一点是 InnoDB 的 <em>next-key lock</em>：走二级索引扫描时，
 * 除了命中的行，<b>行与行之间的间隙也会被锁</b>，于是"往同一柜机插新格口"这种
 * 毫不相关的写也会被阻塞——这不是理论，第 9 刀用 data_locks 实测取证。
 *
 * <p>所以这里锁完仍然走条件更新（而不是裸 updateById）：锁可能因超时/事务边界失效，
 * 条件更新是最后一道防线。<b>锁只负责减少冲突，正确性由条件写保证</b>——
 * 这句话是本刀最值得被追问到的点。
 */
@Service
@ConditionalOnProperty(name = "cabinet.alloc.strategy", havingValue = "pessimistic", matchIfMissing = true)
public class PessimisticSlotAllocator implements SlotAllocator {

    private final BizCompartmentMapper slotMapper;

    public PessimisticSlotAllocator(BizCompartmentMapper slotMapper) {
        this.slotMapper = slotMapper;
    }

    @Override
    public AllocatedSlot allocate(Long cabinetId, SizeType required, Long orderId) {
        List<SizeType> acceptable = SizeType.acceptanceOrder(required);

        // 候选定义不写在本类里：“可分配”在四个分配器 + 预扣集合 + 校准上必须是同一条判据
        // （见 SlotCandidateQuery）。“门开着”与“有遗留物”的格口不能出现在候选里。
        List<BizCompartment> candidates = slotMapper.selectList(
                SlotCandidateQuery.assignable(cabinetId, acceptable)
                        // 锁住扫描到的候选行；必须在事务里调用（StorageOrderService 已标注）
                        .last("for update"));

        if (candidates.isEmpty()) {
            throw new BizException(ResultCode.SLOT_UNAVAILABLE, "该柜机没有可用格口");
        }

        // 偏好顺序在内存里排：候选最多 24 行，不值得为此写数据库方言排序，
        // 而且排序规则属于业务而非存储细节
        BizCompartment chosen = candidates.stream()
                .min(Comparator.comparingInt(s -> acceptable.indexOf(s.getSizeType())))
                .orElseThrow();

        int hit = slotMapper.reserveSlot(chosen.getId(), orderId);
        if (hit == 0) {
            // 拿到锁还被抢走，说明锁已经失效（超时或事务边界问题），必须报警而不是静默重试
            throw new BizException(ResultCode.SLOT_RACE_LOST, "格口分配冲突，请重试");
        }
        return new AllocatedSlot(chosen.getId(), chosen.getSizeType(), 1);
    }

    @Override
    public String strategy() {
        return "pessimistic";
    }
}
