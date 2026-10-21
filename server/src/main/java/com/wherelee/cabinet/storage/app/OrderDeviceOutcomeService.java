package com.wherelee.cabinet.storage.app;

import com.wherelee.cabinet.platform.scheduling.DelayTaskService;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.device.api.CommandAction;
import com.wherelee.cabinet.device.api.CommandOutcome;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.OrderStatus;
import com.wherelee.cabinet.domain.enums.TaskType;
import com.wherelee.cabinet.locker.api.Sensing;
import com.wherelee.cabinet.locker.api.SlotPort;
import com.wherelee.cabinet.locker.api.SlotQueryPort;
import com.wherelee.cabinet.locker.api.SlotSnapshot;
import com.wherelee.cabinet.storage.api.FundPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 设备结果到订单状态的映射，以及关门收敛与计费起点。
 *
 * <p>为什么从订单用例里拆出来：这一簇回答的是“设备说它做了什么，订单该变成什么”，
 * 判据是<b>正在执行哪个动作</b>而不是当前状态；它与下单、取件、凭据这些“用户意图”路径
 * 共用订单实体，但读的人不需要同时理解整套用例。
 *
 * <p>拆出去之后，{@code StorageOrderService} 只负责“什么时候该问设备”，
 * 这里负责“设备答完之后订单怎么变”。
 */
@Service
public class OrderDeviceOutcomeService {

    private static final Logger log = LoggerFactory.getLogger(OrderDeviceOutcomeService.class);

    private final SlotPort states;
    private final SlotQueryPort slotQueries;
    private final FundPort funds;
    /** 调度器延迟拿取：handler 反过来依赖订单服务，直接注入会形成构造环。 */
    private final ObjectProvider<DelayTaskService> taskScheduler;

    /**
     * 开门后的容错期（分钟，第 13B 刀）：这段时间不计费，给用户挑包、放件、翻找。
     *
     * <p>取代原来的 {@code door-grace-minutes}：“多久该提醒”与“多久该起计”本质上是
     * 同一个时刻的两个说法，存两个近似参数总有一天会被配成不一样。
     */
    @Value("${cabinet.door-watch.first-remind-minutes:3}")
    private long firstRemindMinutes;

    public OrderDeviceOutcomeService(SlotPort states, SlotQueryPort slotQueries, FundPort funds,
                                     ObjectProvider<DelayTaskService> taskScheduler) {
        this.states = states;
        this.slotQueries = slotQueries;
        this.funds = funds;
        this.taskScheduler = taskScheduler;
    }

    /**
     * 设备结果到订单状态的映射。
     *
     * <p><b>判据是“正在执行哪个动作”，不是“当前状态”。</b>
     * 为什么不能看状态：本方法跑在 OPEN 已把 RESERVED 推成 OPENING <em>之后</em>，
     * 此时“OPENING”既可能意味着“门开了、件在里面”，也可能意味着“门根本没开”。
     * 拿状态做判就会把“开柜失败”当成“要人工”，而把“关门时谎报”当成“重试一下就好”——两者恰好反了。
     *
     * <p>按动作判就干净了：
     * <ul>
     *   <li>{@code OPEN} 失败 → 件还在用户手里 → 退回 {@code RESERVED}（可重试、可换柜机，不打扰运营）；</li>
     *   <li>{@code CLOSE_VERIFY} 失败/谎报 → 不推进也不回退，只标传感器矛盾（S-16 后它有自己的自助出口）；</li>
     *   <li>{@code OPEN_TEMP} 失败 → 件在里面但没丢 → 保持 {@code ACTIVE}，用户可再试。</li>
     * </ul>
     */
    public void apply(BizStorageOrder order, CommandAction action, CommandOutcome outcome) {
        if (outcome.stale()) {
            // 旧事件：什么都不改（设备重放不得把新状态覆盖成旧事件）
            return;
        }
        if (outcome.businessSuccess()) {
            switch (action) {
                case OPEN -> {
                    // **这里记的是“回执说门开了”，不是“门真的开了”**：判据只有设备那一句 success=true，
                    // 没有独立门磁佐证。谎报开门为什么可接受：头 30 分钟免费兑住了当场发现的情况，
                    // 而当场没发现时还有 19D 的门未关阶梯与工单接手。
                    states.afterDoorOpened(order.getSlotId(), order.getCustomerId());
                    // 19A：开柜瞬间就是计费起点，不再等关门、也不靠容错到期去“补一个起点”。
                    // 头 30 分钟免费由计价算法扣除，所以“开了门没放东西”与“开了门忘了关门”都会自然产生费用。
                    startBilling(order, LocalDateTime.now());
                    log.info("格口已开门并落定计费起点 orderNo={} slotId={} 起点={}",
                            order.getOrderNo(), order.getSlotId(), order.getStartedAt());
                    scheduleDoorWatch(order);
                }
                case OPEN_TEMP -> {
                    order.setTempOpenCount(order.getTempOpenCount() == null ? 1 : order.getTempOpenCount() + 1);
                    order.transitTo(OrderStatus.TEMP_OPEN);
                    states.afterDoorOpened(order.getSlotId(), order.getCustomerId());
                    scheduleDoorWatch(order);
                }
                case CLOSE_VERIFY -> closeVerified(order);
                default -> throw new BizException(ResultCode.PARAM_INVALID, "不支持的动作：" + action);
            }
            return;
        }

        // 失败分支：先问“件在谁手里”
        switch (action) {
            case OPEN -> {
                if (order.getStatus() == OrderStatus.OPENING) {
                    order.transitTo(OrderStatus.RESERVED);
                }
                log.info("开柜未成功，已退回可重试态 orderNo={} fault={}", order.getOrderNo(), outcome.faultType());
            }
            case OPEN_TEMP -> {
                if (order.getStatus() == OrderStatus.TEMP_OPEN) {
                    order.transitTo(OrderStatus.ACTIVE);
                }
            }
            // 关门校验失败/谎报：**不再一律转 ABNORMAL 等人工**（S-16）。
            // 但也不能当成已存：件可能正在里面，假装已存就会把这个格子卖给下一位。
            // 所以：状态不动（计不计费交给容错计时），只把矛盾本身记下来。
            case CLOSE_VERIFY, FORCE_OPEN -> {
                states.markSensorConflict(order.getSlotId(),
                        "设备称关门成功但门磁不认（谎报或门磁卡住），需现场确认");
                log.warn("关门未被门磁确认，不推进订单状态 orderNo={} fault={}",
                        order.getOrderNo(), outcome.faultType());
            }
            default -> order.transitTo(OrderStatus.ABNORMAL);
        }
    }

    /**
     * 关门校验的业务收敛：<b>设备自称成功不算，门磁说了才算</b>。
     */
    private void closeVerified(BizStorageOrder order) {
        applyCloseVerified(order, states.sense(order.getCabinetId(), order.getSlotId()));
    }

    /**
     * 关门收敛。<b>它只回答“门关了没有”，不回答“柜内空了没有”</b>：
     * 后者只在结束订单那一刻判（结束三条件），拿它卡住投件/临时开柜的关门是错的——
     * 投件时柜内本来就该有东西。
     */
    public void applyCloseVerified(BizStorageOrder order, Sensing sensing) {
        if (!sensing.doorClosed()) {
            // 指令层成功而门磁不认：不推进。宁可让用户再点一次，也不能把“没关上”记成已存
            states.markSensorConflict(order.getSlotId(), "收到关门校验但门磁仍报开着，需现场确认");
            log.warn("关门校验未被门磁确认 orderNo={} slotId={}", order.getOrderNo(), order.getSlotId());
            return;
        }
        states.afterDoorClosed(order.getSlotId(), order.getCustomerId());
        if (order.getStatus() == OrderStatus.TEMP_OPEN) {
            // 中途取物后又关回来：本来就在计费，起点不动（I10）
            order.transitTo(OrderStatus.ACTIVE);
        } else if (order.getStatus() == OrderStatus.OPENING || order.getStatus() == OrderStatus.RESERVED) {
            // 容错期满已被巡检推进成 ACTIVE 时走不到这里；走到这里说明这是正常的投件关门
            if (!states.occupy(order.getSlotId(), order.getId())) {
                throw new BizException(ResultCode.SYSTEM_ERROR,
                        "格口状态与订单不一致，需人工核对 slotId=" + order.getSlotId());
            }
            // 格口从“预占”变“真有件”：两个状态分开存，事故时才能分辨件在不在柜里
            order.transitTo(OrderStatus.STORED);
            order.transitTo(OrderStatus.ACTIVE);
        }
        startBilling(order, LocalDateTime.now());
        // 门关了就解除“门未关”异常（这类可以自动，判据只看门）；
        // “柜内有没有东西”到这里不判：它属于结束时的凭据，不属于关门这件事
        states.tryAutoRecover(order.getSlotId(), sensing);
    }

    /**
     * 落定计费起点。<b>一旦写过就不再改（不变量 I10）</b>：结算、逾期看管、账单展示都读它，
     * 改它就等于改掉已经发生的历史。
     *
     * <p>19A 定稿：起点就是**首次开柜成功的时刻**（头 30 分钟免费由计价扣除，不靠这里推）。
     */
    public void startBilling(BizStorageOrder order, LocalDateTime start) {
        if (order.getStartedAt() != null) {
            return;
        }
        order.setStartedAt(start);
        // “多久算逾期”由已定的总额封顶天数推出（capDays=0 表示不限则不设 deadline）
        int capDays = funds.capDaysOf(order.getPricingSnapshot());
        order.setExpectedFinishAt(capDays > 0 ? start.plusDays(capDays) : null);
        var scheduler = taskScheduler.getIfAvailable();
        if (scheduler != null && order.getExpectedFinishAt() != null) {
            scheduler.schedule(TaskType.OVERDUE_PICKUP,
                    order.getOrderNo(), order.getTenantId(), order.getExpectedFinishAt());
        }
    }

    /**
     * 能不能做关门校验。<b>判据是“有没有一扇开着的门要关”，而不是“单在哪个状态”</b>。
     */
    public boolean mayCloseVerify(BizStorageOrder order) {
        if (order.getStatus() == OrderStatus.OPENING || order.getStatus() == OrderStatus.TEMP_OPEN) {
            return true;
        }
        // 已在计费但门还开着：该让他关上（关上后才能结束）
        SlotSnapshot slot = slotQueries.slot(order.getSlotId()).orElse(null);
        return order.getStatus() == OrderStatus.ACTIVE && slot != null && slot.doorOpen();
    }

    /** 登记“门开未关”看管，并把容错到期时刻定下来（两者是同一个时刻）。 */
    public void scheduleDoorWatch(BizStorageOrder order) {
        LocalDateTime toleranceUntil = LocalDateTime.now().plusMinutes(toleranceOf(order));
        if (order.getToleranceUntil() == null) {
            // 临时开柜时早已有过容错期：不得把起点刷新，否则“又开一次门”会把计费起点往后推
            order.setToleranceUntil(toleranceUntil);
        }
        var scheduler = taskScheduler.getIfAvailable();
        if (scheduler == null) {
            return;
        }
        scheduler.schedule(TaskType.DOOR_NOT_CLOSED,
                order.getOrderNo(), order.getTenantId(), order.getToleranceUntil());
    }

    /**
     * 这张单的容错期：<b>算价与计时必须是同一版策略</b>。
     *
     * <p>不然会出现“按站点 A 的价收费，却用全局的容错时长”，两边都能自圆其说但没人能对账。
     * 没发布过策略时退回配置默认值，与下单冻结时取价的回退路径一致。
     */
    private long toleranceOf(BizStorageOrder order) {
        long configured = funds.toleranceMinutesOf(order.getSiteId());
        return configured < 0 ? firstRemindMinutes : configured;
    }
}
