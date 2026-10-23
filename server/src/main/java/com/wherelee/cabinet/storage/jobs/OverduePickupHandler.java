package com.wherelee.cabinet.storage.jobs;

import com.wherelee.cabinet.infrastructure.task.TaskOrders;
import com.wherelee.cabinet.platform.scheduling.TaskHandler;
import com.wherelee.cabinet.storage.api.FundPort;
import com.wherelee.cabinet.domain.entity.BizDelayTask;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.OrderStatus;
import com.wherelee.cabinet.domain.enums.TaskType;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;

@Component
public class OverduePickupHandler implements TaskHandler {

    private static final Logger log = LoggerFactory.getLogger(OverduePickupHandler.class);

    private final BizStorageOrderMapper orderMapper;
    private final com.wherelee.cabinet.storage.api.FundPort pricing;
    private final MeterRegistry meterRegistry;
    private final com.wherelee.cabinet.servicedesk.api.WorkOrderIntakePort intake;
    private final com.wherelee.cabinet.servicedesk.api.WorkOrderConsolePort workOrders;

    @Value("${cabinet.scheduler.stranded-days:7}")
    private long strandedDays;

    OverduePickupHandler(BizStorageOrderMapper orderMapper, com.wherelee.cabinet.storage.api.FundPort pricing,
                         MeterRegistry meterRegistry,
                         com.wherelee.cabinet.servicedesk.api.WorkOrderIntakePort intake,
                         com.wherelee.cabinet.servicedesk.api.WorkOrderConsolePort workOrders) {
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

    /**
     * 必须在无外层事务下跑。
     *
     * <p>这个 handler 会同步调工单系统（开单、升级、派人）。有外层事务时，
     * {@code openLongRunning} 里那个 try/catch 挡不住失败：下游是 participating transaction，
     * 抛异常会把共享的物理事务标成 rollback-only，于是“订单推进 EXPIRED”跟着回滚。
     * 而这是小时级巡检，工单侧抖一次就白跑一轮。
     *
     * <p>代价显式承担：handle 里的每一步都必须可重入。
     * 推进 EXPIRED 是带 version 的条件更新且判返回值；两个 counter 只在写成功之后记；
     * 开单与升级靠 dedup_key 幂等。所以“业务提交了但 markDone 失败”重跑一遍是安全的。
     */
    @Override
    public boolean transactional() {
        return false;
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
            // 必须判返回值：并发对手是用户取件（pickup 写同一行、version +1）。落空说明用户已经赢了，
            // 此时下面的 counter、ERROR 与滞留工单全都不能做——否则用户已经把东西取走了，
            // 系统却开出一张“需人工到场处置”的工单、打一条 ERROR、还记脏两个指标。
            // 而下面的 capped 只看 heldMinutes（从 startedAt 算），与 version 无关，
            // 所以不拦住就会真的走到开单那一步。
            // 返回 true 让下一轮重读：真终态会被上面的终态守卫收掉，若只是别的并发写则下一轮重来。
            if (orderMapper.updateById(order) == 0) {
                BizStorageOrder now = orderMapper.selectById(order.getId());
                log.info("逾期推进 EXPIRED 落空，订单已被并发处置，本轮不记指标也不开单 orderNo={} 现状={}",
                        order.getOrderNo(), now == null ? "已不存在" : now.getStatus());
                return true;
            }
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
            // 开单没成就继续排：本类已经 transactional()=false，“推进 EXPIRED”在上一步就提交了，
            // 此时若返回 false，任务直接结束，再没有下一轮来补开这张单（工单会永久丢失）。
            return !openLongRunning(order, heldMinutes);
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
     *
     * <p><b>为什么返回 boolean</b>：本类 {@code transactional()=false} 之后，“推进 EXPIRED”
     * 已经单独提交了。此时若开单失败还返回“不需要再排”，任务就直接结束、再没有人来补这张单。
     * 所以返回值是“这单交出去了没有”，调用方据此决定要不要继续排下一轮。
     *
     * <p>下面那个 try/catch 以前是<b>无效</b>的：外层事务把 handle 包住时，
     * {@code onOrderEvent} 是 participating transaction，它抛异常会把共享的物理事务标成
     * rollback-only，catch 住也没用——提交时照样 {@code UnexpectedRollbackException}。
     * 改成 {@code transactional()=false} 之后下游各自成事务，它才真的挡住。
     */
    private boolean openLongRunning(BizStorageOrder order, long heldMinutes) {
        try {
            intake.onOrderEvent(order, com.wherelee.cabinet.servicedesk.api.WorkOrderType.LONG_RUNNING,
                    false, "计费已到封顶（最多 20 小时单价），需联系用户取件",
                    "超 48 小时未结束：需联系客户", java.time.LocalDateTime.now().plusHours(2));
            Long woId = workOrders.openIdOf(order.getSlotId(),
                    com.wherelee.cabinet.servicedesk.api.WorkOrderType.LONG_RUNNING);
            if (woId == null) {
                // 开单没落成（幂等键冲突之类），下一轮再来
                return false;
            }
            // 升级：进“必须处理”并产出电话任务与站内消息（去重靠 dedup_key）
            workOrders.escalate(order.getSlotId(),
                    com.wherelee.cabinet.servicedesk.api.WorkOrderType.LONG_RUNNING,
                    "订单超 48 小时未结束：必须联系客户取件或结单");
            if (heldMinutes >= 72L * 60L) {
                // 72 小时：自动派线下，但不动线上父单的状态（机器不能替人下结论）
                workOrders.openFieldTwin(woId, "用户超 72 小时未取件，需到场处理");
            }
            return true;
        } catch (RuntimeException e) {
            // 开单失败不能拖挂看管收口：单已滞留的事实已经计下，工单下一轮会再试
            log.error("滞留工单开单失败，下一轮重试 orderNo={}", order.getOrderNo(), e);
            return false;
        }
    }
}
