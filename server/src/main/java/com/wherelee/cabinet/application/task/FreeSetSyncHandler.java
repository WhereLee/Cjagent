package com.wherelee.cabinet.application.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.funds.app.PricingPolicy;
import com.wherelee.cabinet.storage.api.FundPort;
import com.wherelee.cabinet.device.app.DeviceCommandService;
import com.wherelee.cabinet.funds.app.ReconcileReport;
import com.wherelee.cabinet.funds.app.ReconcileService;
import com.wherelee.cabinet.locker.api.SlotPort;
import com.wherelee.cabinet.locker.app.SlotCandidateQuery;
import com.wherelee.cabinet.storage.app.StorageOrderService;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.locker.domain.BizCabinet;
import com.wherelee.cabinet.locker.domain.BizCompartment;
import com.wherelee.cabinet.domain.entity.BizDelayTask;
import com.wherelee.cabinet.funds.domain.BizDeposit;
import com.wherelee.cabinet.device.domain.BizDeviceCommand;
import com.wherelee.cabinet.device.domain.BizFaultEvent;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.funds.api.DepositStatus;
import com.wherelee.cabinet.device.api.FaultType;
import com.wherelee.cabinet.domain.enums.OrderStatus;
import com.wherelee.cabinet.locker.api.SlotStatus;
import com.wherelee.cabinet.locker.api.SizeType;
import com.wherelee.cabinet.domain.enums.TaskType;
import com.wherelee.cabinet.locker.infra.SlotPreDeductionService;
import com.wherelee.cabinet.locker.infra.BizCabinetMapper;
import com.wherelee.cabinet.locker.infra.BizCompartmentMapper;
import com.wherelee.cabinet.funds.infra.BizDepositMapper;
import com.wherelee.cabinet.device.infra.BizDeviceCommandMapper;
import com.wherelee.cabinet.device.infra.BizFaultEventMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.List;

@Component
public class FreeSetSyncHandler implements TaskHandler {

    private static final Logger log = LoggerFactory.getLogger(FreeSetSyncHandler.class);

    private final BizCabinetMapper cabinetMapper;
    private final BizCompartmentMapper slotMapper;
    private final SlotPreDeductionService preDeduction;

    FreeSetSyncHandler(BizCabinetMapper cabinetMapper, BizCompartmentMapper slotMapper,
                       SlotPreDeductionService preDeduction) {
        this.cabinetMapper = cabinetMapper;
        this.slotMapper = slotMapper;
        this.preDeduction = preDeduction;
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
            for (SizeType size : SizeType.values()) {
                // 共用同一条“可分配”判据：校准若把“门开着/有遗留物”的格口写回集合，
                // 就是把“DB 已经拦住了”这件事又放回 Redis 里放行
                List<Long> free = slotMapper.selectList(
                                SlotCandidateQuery.assignable(cabinet.getId(), size))
                        .stream().map(BizCompartment::getId).toList();
                preDeduction.replaceFreeSet(cabinet.getId(), size, free);
            }
        }
        log.info("空闲集合校准完成，柜机数={}", cabinets.size());
        return true;
    }
}

/**
 * 对账批次：调 {@link ReconcileService}，本类只负责“什么时候跑”与“要不要再排”。
 *
 * <p>为什么不在 handler 里写查询：对账逻辑还有第二个入口（后台人工触发、第 14 刀的修复提案），
 * 两处各写一遍差异判据，迟早出现“调度说平、后台说不平”——那时候没人能判断哪个对。
 */
