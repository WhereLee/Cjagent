package com.wherelee.cabinet.locker.jobs;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.domain.entity.BizDelayTask;
import com.wherelee.cabinet.domain.enums.TaskType;
import com.wherelee.cabinet.locker.api.SlotPoolPort;
import com.wherelee.cabinet.locker.domain.BizCabinet;
import com.wherelee.cabinet.locker.infra.BizCabinetMapper;
import com.wherelee.cabinet.platform.scheduling.TaskHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 空闲集合校准：按 DB 真相重建 Redis 准入集合。
 *
 * <p>它是 {@code PreallocSlotAllocator} 的收敛路径。预占之后订单事务回滚、或进程崩在
 * 预占与提交之间，都会留下一个"既没卖出去、也不在集合里"的格口——方向是少卖不是超卖，
 * 这一轮跑完就把它补回可卖状态。
 *
 * <p>为什么本类不写查询：怎么算"可分配"是 locker 的事，那条判据同时被四个分配器、
 * 预占集合与后台人工重建共用（{@code SlotCandidateQuery.assignable}）。在这里再写一遍，
 * 迟早出现"定时校准说有位、下单说没位"——那时候没人能判断哪个对。
 *
 * <p>校准期间集合短暂为空，这段时间少卖。要绝对无缝得改成"写临时 key + RENAME"，
 * 本项目不需要这个复杂度。
 */
@Component
public class FreeSetSyncHandler implements TaskHandler {

    private static final Logger log = LoggerFactory.getLogger(FreeSetSyncHandler.class);

    private final BizCabinetMapper cabinetMapper;
    private final SlotPoolPort slotPool;

    FreeSetSyncHandler(BizCabinetMapper cabinetMapper, SlotPoolPort slotPool) {
        this.cabinetMapper = cabinetMapper;
        this.slotPool = slotPool;
    }

    @Override
    public TaskType type() {
        return TaskType.FREE_SET_SYNC;
    }

    @Override
    public boolean handle(BizDelayTask task) {
        List<BizCabinet> cabinets = cabinetMapper.selectList(Wrappers.<BizCabinet>lambdaQuery()
                .eq(BizCabinet::getTenantId, TenantContext.current()));
        for (BizCabinet cabinet : cabinets) {
            slotPool.syncAllFreeSets(cabinet.getId());
        }
        log.info("空闲集合校准完成，柜机数={}", cabinets.size());
        return true;
    }
}
