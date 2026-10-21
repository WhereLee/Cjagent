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
public class OverduePickupHandler implements TaskHandler {

    private static final Logger log = LoggerFactory.getLogger(OverduePickupHandler.class);

    private final BizStorageOrderMapper orderMapper;
    private final PricingPolicy pricing;
    private final MeterRegistry meterRegistry;
    private final com.wherelee.cabinet.servicedesk.app.WorkOrderIntake intake;
    private final com.wherelee.cabinet.servicedesk.app.WorkOrderService workOrders;

    @Value("${cabinet.scheduler.stranded-days:7}")
    private long strandedDays;

    OverduePickupHandler(BizStorageOrderMapper orderMapper, PricingPolicy pricing,
                         MeterRegistry meterRegistry,
                         com.wherelee.cabinet.servicedesk.app.WorkOrderIntake intake,
                         com.wherelee.cabinet.servicedesk.app.WorkOrderService workOrders) {
        this.orderMapper = orderMapper;
        this.pricing = pricing;
        this.meterRegistry = meterRegistry;
        this.intake = intake;
        this.workOrders = workOrders;
    }

    @Override
    public TaskType type() {
        return TaskType.OVERDUE_PICKUP;
    }

    @Override
    public boolean handle(BizDelayTask task) {
        BizStorageOrder order = TaskOrders.find(orderMapper, task.getBizKey());
        if (order == null || order.getStatus().isTerminal()) {
            return false;
        }
        // 还没开始计费的单不由这里管：RESERVED 有超时释放，OPENING 有门未关
        if (order.getStatus() != OrderStatus.ACTIVE && order.getStatus() != OrderStatus.TEMP_OPEN
                && order.getStatus() != OrderStatus.EXPIRED) {
            return false;
        }

        LocalDateTime deadline = order.getExpectedFinishAt() != null
                ? order.getExpectedFinishAt() : order.getCreateTime();
        long overdueMinutes = Math.max(0L,
                java.time.Duration.between(deadline, LocalDateTime.now()).toMinutes());
        // 封顶与“超 48 小时”必须看**总持有时长**（开柜到现在），不能看“逾期了多少分钟”：
        // `expectedFinishAt` 本身就是起点 + 封顶天数，拿它再去做封顶判断会把 48 小时推成约 96 小时
        // （以前就错在这里，用户定的 48h 工单实际近两天后才开）。同一文件里门未关看管用的就
        // 是 startedAt→now，两处必须同一口径。
        long heldMinutes = order.getStartedAt() == null ? overdueMinutes
                : Math.max(0L, java.time.Duration.between(order.getStartedAt(), LocalDateTime.now()).toMinutes());

        if (order.getStatus() == OrderStatus.ACTIVE || order.getStatus() == OrderStatus.TEMP_OPEN) {
            order.transitTo(OrderStatus.EXPIRED);
            orderMapper.updateById(order);
            meterRegistry.counter("cabinet.order.overdue", "size", order.getSizeType().name()).increment();
            log.info("订单逾期转 EXPIRED orderNo={} 逾期点={} 已逾 {} 分钟", order.getOrderNo(),
                    order.getExpectedFinishAt(), overdueMinutes);
        }

        boolean capped = pricing.isCapped(order.getPricingSnapshot(), heldMinutes);
        // 两个时长各管各的，不能合并：
        //   · 封顶（capped）看**总持有时长**，“最多收 20 小时单价”说的就是从头到尾占了多久；
        //   · 看管窗口（stranded-days）看**从应结束时刻起算的逾期多久**，它是“盯了多久还没人接”，
        //     对 capDays=0 的旧快照还要靠它兜底（那类单永远到不了顶）。
        if (capped || overdueMinutes >= strandedDays * 24L * 60L) {
            // 滞留：价格到顶或盯够天数，继续排只是刷日志，该交给人（而不是悄悄排到天荒地老）
            meterRegistry.counter("cabinet.order.stranded",
                    "reason", capped ? "price-capped" : "watch-expired").increment();
            log.error("订单滞留需人工处置 orderNo={} 已持有分钟={} 逾期分钟={} 封顶已到={} 盯了 {} 天",
                    order.getOrderNo(), heldMinutes, overdueMinutes, capped, strandedDays);
            openLongRunning(order, heldMinutes);
            return false;
        }
        return true;
    }

    /**
     * 19D 第 3 条：到顶就开工单，不再只是日志里一响。
     *
     * <p>用户给的两个数：<b>超 48 小时未结束→工单联系用户</b>，<b>超 72 小时→线下运维可介入</b>。
     * 两档都看**从计费起点算起的总持有时长**（`heldMinutes`）：拿“逾期分钟”判会凭空多等一周期。
     *
     * <p>开单与升级都是幂等的：看管任务每小时都会重进这里。
     */
    private void openLongRunning(BizStorageOrder order, long heldMinutes) {
        try {
            intake.onOrderEvent(order, com.wherelee.cabinet.servicedesk.api.WorkOrderType.LONG_RUNNING,
                    false, "计费已到封顶（最多 20 小时单价），需联系用户取件",
                    "超 48 小时未结束：需联系客户", java.time.LocalDateTime.now().plusHours(2));
            Long woId = workOrders.openIdOf(order.getSlotId(),
                    com.wherelee.cabinet.servicedesk.api.WorkOrderType.LONG_RUNNING);
            if (woId == null) {
                return;
            }
            // 升级：进“必须处理”并产出电话任务与站内消息（去重靠 dedup_key）
            workOrders.escalate(order.getSlotId(),
                    com.wherelee.cabinet.servicedesk.api.WorkOrderType.LONG_RUNNING,
                    "订单超 48 小时未结束：必须联系客户取件或结单");
            if (heldMinutes >= 72L * 60L) {
                // 72 小时：自动派线下，但不动线上父单的状态（机器不能替人下结论）
                workOrders.openFieldTwin(woId, "用户超 72 小时未取件，需到场处理");
            }
        } catch (RuntimeException e) {
            // 开单失败不能拖挂看管收口：单已滞留的事实已经计下，工单下一轮会再试
            log.error("滞留工单开单失败，下一轮重试 orderNo={}", order.getOrderNo(), e);
        }
    }
}

/** 押金安全网：订单已终态但押金还挂着，就把冻结退回去（幂等由状态条件更新保证）。 */
