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
