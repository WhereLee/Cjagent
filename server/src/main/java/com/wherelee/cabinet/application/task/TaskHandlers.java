package com.wherelee.cabinet.application.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.application.billing.PricingPolicy;
import com.wherelee.cabinet.application.point.OrderFundService;
import com.wherelee.cabinet.application.device.DeviceCommandService;
import com.wherelee.cabinet.application.reconcile.ReconcileReport;
import com.wherelee.cabinet.application.reconcile.ReconcileService;
import com.wherelee.cabinet.application.storage.CompartmentStateService;
import com.wherelee.cabinet.application.storage.SlotCandidateQuery;
import com.wherelee.cabinet.application.storage.StorageOrderService;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.entity.BizCabinet;
import com.wherelee.cabinet.domain.entity.BizCompartment;
import com.wherelee.cabinet.domain.entity.BizDelayTask;
import com.wherelee.cabinet.domain.entity.BizDeposit;
import com.wherelee.cabinet.domain.entity.BizDeviceCommand;
import com.wherelee.cabinet.domain.entity.BizFaultEvent;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.DepositStatus;
import com.wherelee.cabinet.domain.enums.FaultType;
import com.wherelee.cabinet.domain.enums.OrderStatus;
import com.wherelee.cabinet.domain.enums.SlotStatus;
import com.wherelee.cabinet.domain.enums.SizeType;
import com.wherelee.cabinet.domain.enums.TaskType;
import com.wherelee.cabinet.infrastructure.alloc.SlotPreDeductionService;
import com.wherelee.cabinet.infrastructure.mapper.BizCabinetMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizCompartmentMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizDepositMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizDeviceCommandMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizFaultEventMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 延迟任务处理器集合。
 *
 * <p><b>每个 handler 都必须可重入</b>：租约只保证"同时一个人做"，不保证"一生做一次"
 * （业务提交前崩溃、租约过期被接管就会重跑）。所以所有状态推进都写成条件更新/唯一键，
 * 而不是"查一下再改"。
 *
 * <p>能复用业务用例就复用（例如超时释放直接走 {@code StorageOrderService.cancel}）：
 * 调度里重写一遍释放逻辑，等于给同一件事写两套真相，迟早漂移。
 */
public final class TaskHandlers {

    private static final Logger log = LoggerFactory.getLogger(TaskHandlers.class);

    private TaskHandlers() {
    }

    /** 按单号取订单（调度没有 HTTP 上下文，但已在 worker 里包了租户上下文，拦截器照旧生效）。 */
    private static BizStorageOrder find(BizStorageOrderMapper mapper, String orderNo) {
        return mapper.selectOne(Wrappers.<BizStorageOrder>lambdaQuery()
                .eq(BizStorageOrder::getOrderNo, orderNo));
    }

    /** 超时未投件：走业务取消路径释放格口与资金。 */
    @Component
    public static class SlotReleaseHandler implements TaskHandler {

        private final BizStorageOrderMapper orderMapper;
        private final StorageOrderService orders;

        SlotReleaseHandler(BizStorageOrderMapper orderMapper, StorageOrderService orders) {
            this.orderMapper = orderMapper;
            this.orders = orders;
        }

        @Override
        public TaskType type() {
            return TaskType.SLOT_RELEASE;
        }

        @Override
        public boolean handle(BizDelayTask task) {
            BizStorageOrder order = find(orderMapper, task.getBizKey());
            if (order == null) {
                log.info("超时释放跳过：订单不存在 orderNo={}", task.getBizKey());
                return false;
            }
            if (order.getStatus() != OrderStatus.RESERVED) {
                // 已投件或已取消：本任务什么都不该做（重入的关键，不是"跳过报错"）
                log.info("超时释放跳过：当前状态 {}", order.getStatus());
                return false;
            }
            if (order.getToleranceUntil() != null) {
                // 门曾经开过：件很可能已经在柜里。这时不能再“自动取消 + 退钱 + 释放格口”——
                // 那正好造出最难处理的现场：“钱退了、件留下了、格子卖给了第三个人”。
                // 接手的是门未关看管（落定计费）→ 逾期（转 EXPIRED）→ 封顶后交对账与运维清单。
                log.info("超时释放跳过：该单已开过门，改由门未关/逾期看管接手 orderNo={}", order.getOrderNo());
                return false;
            }
            orders.cancel(order.getCustomerId(), order.getOrderNo());
            log.info("超时未投件，已自动释放 orderNo={} slotId={}", order.getOrderNo(), order.getSlotId());
            return false;
        }
    }

    /**
     * 门开太久的看管：S-16 之后它不再是“人工事件”，而是<b>计费事件</b>。
     *
     * <p>做三件事：落定计费起点、让单进入计费态、告警并续排提醒；到封顶就停止自转，
     * 交对账与运维清单。<b>不转 ABNORMAL、不自动撑销</b>。
     *
     * <p>原来这里的行为是“OPENING 超时转 ABNORMAL 等人工”。改的理由是经济的：柜机散布全城
     * 多点位，派一次出勤的成本远高于一个格口被占的损耗；而“每次未关门都找人”最后一定
     * 变成没人接的工单——等于没有规则。钱会把人叫回来，不需要人盯屏幕。
     *
     * <p>也不自动撑销：那会把一个可能真有件的格口标成可分配，下一个用户开开的就是别人那件。
     *
     * <p>{@link #transactional()} 返回 false：设备探测在真接上 MQTT 后是一次 RPC，
     * 不能拿着数据库连接等它回答（第 11 刀拆事务的同一理由）；收敛自己在
     * {@code StorageOrderService.toleranceExpired} 里成一个短事务。
     */
    @Component
    public static class DoorNotClosedHandler implements TaskHandler {
    
        private final BizStorageOrderMapper orderMapper;
        private final BizFaultEventMapper faultMapper;
        private final StorageOrderService orders;
        private final CompartmentStateService states;
        private final PricingPolicy pricing;
    
        DoorNotClosedHandler(BizStorageOrderMapper orderMapper, BizFaultEventMapper faultMapper,
                             StorageOrderService orders, CompartmentStateService states, PricingPolicy pricing) {
            this.orderMapper = orderMapper;
            this.faultMapper = faultMapper;
            this.orders = orders;
            this.states = states;
            this.pricing = pricing;
        }
    
        @Override
        public TaskType type() {
            return TaskType.DOOR_NOT_CLOSED;
        }
    
        @Override
        public boolean transactional() {
            return false;
        }
    
        @Override
        public boolean handle(BizDelayTask task) {
            BizStorageOrder order = find(orderMapper, task.getBizKey());
            if (order == null || order.getStatus().isTerminal()) {
                return false;
            }
            // 到顶就停：再盯下去也不会多收一分，继续自转只会刷日志。
            // 停看管不等于丢现场：格口的 DOOR_OPEN 异常还在，对账会把未关门数计成指标。
            if (order.getStartedAt() != null && pricing.isCapped(order.getPricingSnapshot(),
                    java.time.Duration.between(order.getStartedAt(), LocalDateTime.now()).toMinutes())) {
                recordFault(order, "柜门长时间未关且计费已到顶，转对账与运维清单");
                return false;
            }
            boolean remindAgain = orders.toleranceExpired(order.getOrderNo(),
                    states.sense(order.getCabinetId(), order.getSlotId()));
            if (remindAgain) {
                recordFault(order, "柜门仍未关闭：已开始计费，直到关门或用户远程结束");
            }
            return remindAgain;
        }
    
        private void recordFault(BizStorageOrder order, String reason) {
            BizFaultEvent event = new BizFaultEvent();
            event.setCabinetId(order.getCabinetId());
            event.setSlotId(order.getSlotId());
            event.setFaultType(FaultType.DOOR_NOT_CLOSED);
            event.setAction(FaultType.Action.ALARM);
            event.setFailCount(0);
            event.setReason(reason);
            faultMapper.insert(event);
        }
    }

    /**
     * 逾期未取：转 EXPIRED（仍非终态，用户随时可来取），并在“再盯下去也不会多收一分”时交人工。
     *
     * <p>不自己“再排一轮”：那会把 handler 变成调度器的依赖（构成循环）。返回 true 让 worker 续排。
     *
     * <p><b为什么要收口</b>：“每小时续排一次，直到终态”在没有终态的单（用户把件忘了、手机丢了）上
     * 就是一张永远没人接的工单。两个出口任一命中就停：
     * ① 快照里的总封顶已到顶（再放也不收钱了）；② 逾期满 stranded-days（封顶配成了不限时的硬兑底）。
     * 停看管不等于丢现场：对账作业会把滞留单计成指标（永远能数出来），后台工单是第 14 刀。
     */
    @Component
    public static class OverduePickupHandler implements TaskHandler {

        private final BizStorageOrderMapper orderMapper;
        private final PricingPolicy pricing;
        private final MeterRegistry meterRegistry;

        @Value("${cabinet.scheduler.stranded-days:7}")
        private long strandedDays;

        OverduePickupHandler(BizStorageOrderMapper orderMapper, PricingPolicy pricing,
                             MeterRegistry meterRegistry) {
            this.orderMapper = orderMapper;
            this.pricing = pricing;
            this.meterRegistry = meterRegistry;
        }

        @Override
        public TaskType type() {
            return TaskType.OVERDUE_PICKUP;
        }

        @Override
        public boolean handle(BizDelayTask task) {
            BizStorageOrder order = find(orderMapper, task.getBizKey());
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

            if (order.getStatus() == OrderStatus.ACTIVE || order.getStatus() == OrderStatus.TEMP_OPEN) {
                order.transitTo(OrderStatus.EXPIRED);
                orderMapper.updateById(order);
                meterRegistry.counter("cabinet.order.overdue", "size", order.getSizeType().name()).increment();
                log.info("订单逾期转 EXPIRED orderNo={} 逾期点={} 已逾 {} 分钟", order.getOrderNo(),
                        order.getExpectedFinishAt(), overdueMinutes);
            }

            boolean capped = pricing.isCapped(order.getPricingSnapshot(), overdueMinutes);
            if (capped || overdueMinutes >= strandedDays * 24L * 60L) {
                // 滞留：价格到顶或盯够天数，继续排只是刷日志，该交给人（而不是悄悄排到天荒地老）
                meterRegistry.counter("cabinet.order.stranded",
                        "reason", capped ? "price-capped" : "watch-expired").increment();
                log.error("订单滞留需人工处置 orderNo={} 逾期分钟={} 封顶已到={} 盯了 {} 天", order.getOrderNo(),
                        overdueMinutes, capped, strandedDays);
                return false;
            }
            return true;
        }
    }

    /** 押金安全网：订单已终态但押金还挂着，就把冻结退回去（幂等由状态条件更新保证）。 */
    @Component
    public static class DepositRefundHandler implements TaskHandler {

        private final BizStorageOrderMapper orderMapper;
        private final OrderFundService funds;

        DepositRefundHandler(BizStorageOrderMapper orderMapper, OrderFundService funds) {
            this.orderMapper = orderMapper;
            this.funds = funds;
        }

        @Override
        public TaskType type() {
            return TaskType.DEPOSIT_REFUND;
        }

        @Override
        public boolean handle(BizDelayTask task) {
            BizStorageOrder order = find(orderMapper, task.getBizKey());
            if (order == null) {
                return false;
            }
            if (!order.getStatus().isTerminal()) {
                // 还没结束，钱本来就该继续冻着：重投到终态之后再处理，而不是判死
                throw new BizException(ResultCode.BIZ_ERROR, "订单未终态，稍后再试 orderNo=" + order.getOrderNo());
            }
            int refunded = funds.refundHangingDeposit(order);
            if (refunded > 0) {
                log.warn("押金悬挂已回收 orderNo={} 退还点数={}", order.getOrderNo(), refunded);
            }
            return false;
        }
    }

    /** 空闲集合校准：把预扣造成的"少卖漂移"按 DB 真相拉回。 */
    @Component
    public static class FreeSetSyncHandler implements TaskHandler {

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
    @Component
    public static class LedgerReconcileHandler implements TaskHandler {

        private final ReconcileService reconcile;
        private final org.springframework.beans.factory.ObjectProvider<DelayTaskService> taskScheduler;

        @Value("${cabinet.scheduler.deposit-sweep-limit:200}")
        private int depositSweepLimit;

        LedgerReconcileHandler(ReconcileService reconcile,
                               org.springframework.beans.factory.ObjectProvider<DelayTaskService> taskScheduler) {
            this.reconcile = reconcile;
            this.taskScheduler = taskScheduler;
        }

        @Override
        public TaskType type() {
            return TaskType.LEDGER_RECONCILE;
        }

        @Override
        public boolean handle(BizDelayTask task) {
            // 任务自带的租户号而不是 TenantContext.current()：worker 已包上上下文，但周期任务的
            // bizKey 本来就是租户号，显式取它才能“这条任务负责哪个租户”说得清
            Long tenantId = Long.valueOf(task.getBizKey());
            ReconcileReport report = reconcile.reconcile(tenantId);
            log.info("对账批次完成 tenant={} 扫描账户={} 差异项={}", report.tenantId(),
                    report.accountsScanned(), report.failedChecks());

            // 只报告不够：差异必须有人接。“单已终态而押金未退”这组登记重试退还，
            // 上限一条批次（剩下的下一轮再接），否则一轮对账能写出万个任务把表埋了
            List<String> hanging = reconcile.hangingDepositOrderNos(tenantId, depositSweepLimit);
            DelayTaskService scheduler = taskScheduler.getIfAvailable();
            if (scheduler != null && !hanging.isEmpty()) {
                LocalDateTime fireAt = LocalDateTime.now().plusMinutes(1);
                for (String orderNo : hanging) {
                    scheduler.schedule(TaskType.DEPOSIT_REFUND, orderNo, tenantId, fireAt);
                }
                log.warn("悬挂押金已登记退还重试 tenant={} 条数={}", tenantId, hanging.size());
            }
            return true;
        }
    }

    /**
     * 设备指令超时回扫：把“下发与收敛不是一个事务”留下的现场收掉。
     *
     * <p><b>{@link #transactional()} 必须为 false</b>：回扫重试里也要等回执（最长 2.5 秒），
     * 外层一开事务就把第 11 刀“等回执不持事务”的拆法重新合上了——那正是第 9 刀压测量出来的瓶颈。
     * 代价显式承担：本 handler 每一步都靠条件更新/幂等键，重跑一遍不会多开门。
     */
    @Component
    public static class CommandRescanHandler implements TaskHandler {

        private final DeviceCommandService devices;
        private final BizDeviceCommandMapper commandMapper;
        private final StorageOrderService orders;

        CommandRescanHandler(DeviceCommandService devices, BizDeviceCommandMapper commandMapper,
                             StorageOrderService orders) {
            this.devices = devices;
            this.commandMapper = commandMapper;
            this.orders = orders;
        }

        @Override
        public TaskType type() {
            return TaskType.COMMAND_RESCAN;
        }

        @Override
        public boolean transactional() {
            return false;
        }

        @Override
        public boolean handle(BizDelayTask task) {
            DeviceCommandService.RescanResult result = devices.rescan(task.getBizKey());
            if (result.outcome() == null) {
                return false;
            }
            BizDeviceCommand command = commandMapper.selectByRequestId(task.getBizKey());
            if (command != null && command.getOrderId() != null) {
                // 订单收敛交给业务侧：它才知道“这个动作失败后件在谁手里”
                orders.convergeDeviceOutcome(command.getOrderId(), command.getAction(), result.outcome());
            }
            // 又超时且还有重试预算 → 让 worker 续排下一轮。不能指望 rescan 内部“顺手再登记”：
            // 此刻它自己的任务行还是 RUNNING，登记会走“不动”那一支，而 markDone 在后——
            // 不返 true 就等于只重试一次就断链（本刀实测）。
            return result.action() == DeviceCommandService.RescanAction.REDISPATCHED
                    && result.outcome() != null
                    && result.outcome().state() == com.wherelee.cabinet.domain.enums.CommandState.TIMEOUT;
        }
    }

    /**
     * 指令扫街：把“卡在中间态”的指令重新推上回扫通道。
     *
     * <p>逐条登记只能盖住“超时”这一种；<b>下发写了、收敛还没跑进程就被杀</b>时，
     * 没有任何人会再提这条指令——没有这一层，第 11 刀拆开事务的代价就没人付。
     * 不直接重发而是登记 COMMAND_RESCAN：幂等键是 (类型, requestId)，重复扫到也不会堆任务，
     * 而收敛只有一处实现。
     */
    @Component
    public static class CommandSweepHandler implements TaskHandler {

        private final BizDeviceCommandMapper commandMapper;
        private final org.springframework.beans.factory.ObjectProvider<DelayTaskService> taskScheduler;

        @Value("${cabinet.scheduler.sweep-stale-minutes:2}")
        private long staleMinutes;

        @Value("${cabinet.scheduler.sweep-batch-size:50}")
        private int sweepBatchSize;

        CommandSweepHandler(BizDeviceCommandMapper commandMapper,
                            org.springframework.beans.factory.ObjectProvider<DelayTaskService> taskScheduler) {
            this.commandMapper = commandMapper;
            this.taskScheduler = taskScheduler;
        }

        @Override
        public TaskType type() {
            return TaskType.COMMAND_SWEEP;
        }

        @Override
        public boolean handle(BizDelayTask task) {
            DelayTaskService scheduler = taskScheduler.getIfAvailable();
            if (scheduler == null) {
                log.warn("没有调度器，指令扫街本轮什么也不能做");
                return true;
            }
            List<String> stuck = commandMapper.findStuckRequestIds(
                    LocalDateTime.now().minusMinutes(staleMinutes), sweepBatchSize);
            LocalDateTime fireAt = LocalDateTime.now();
            for (String requestId : stuck) {
                scheduler.schedule(TaskType.COMMAND_RESCAN, requestId, task.getTenantId(), fireAt);
            }
            if (!stuck.isEmpty()) {
                log.warn("扫到卡住的指令 {} 条，已登记回扫（单轮上限 {}）",
                        stuck.size(), sweepBatchSize);
            }
            return true;
        }
    }
}
