package com.wherelee.cabinet.storage.jobs;

import com.wherelee.cabinet.infrastructure.task.TaskOrders;
import com.wherelee.cabinet.platform.scheduling.TaskHandler;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.funds.app.PricingPolicy;
import com.wherelee.cabinet.storage.api.FundPort;
import com.wherelee.cabinet.device.app.DeviceCommandService;
import com.wherelee.cabinet.funds.app.ReconcileReport;
import com.wherelee.cabinet.funds.app.ReconcileService;
import com.wherelee.cabinet.locker.api.SlotPort;
import com.wherelee.cabinet.locker.app.SlotCandidateQuery;
import com.wherelee.cabinet.device.api.CommandPort;
import com.wherelee.cabinet.storage.api.StorageOrderCommandPort;
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
public class SlotReleaseHandler implements TaskHandler {

    private static final Logger log = LoggerFactory.getLogger(SlotReleaseHandler.class);

    private final BizStorageOrderMapper orderMapper;
    private final StorageOrderCommandPort orders;
    private final CommandPort deviceCommands;
    private final com.wherelee.cabinet.locker.api.SlotPort states;

    SlotReleaseHandler(BizStorageOrderMapper orderMapper, StorageOrderCommandPort orders,
                       CommandPort deviceCommands,
                       com.wherelee.cabinet.locker.api.SlotPort states) {
        this.orderMapper = orderMapper;
        this.orders = orders;
        this.deviceCommands = deviceCommands;
        this.states = states;
    }

    @Override
    public TaskType type() {
        return TaskType.SLOT_RELEASE;
    }

    /** 这张单上是否有一条**成功的**开门指令（不管是首次投件还是临时开柜）。 */
    private boolean anyOpenSucceeded(Long orderId) {
        return deviceCommands.anyOpenSucceeded(orderId);
    }

    @Override
    public boolean handle(BizDelayTask task) {
        BizStorageOrder order = TaskOrders.find(orderMapper, task.getBizKey());
        if (order == null) {
            log.info("超时释放跳过：订单不存在 orderNo={}", task.getBizKey());
            return false;
        }
        if (order.getStatus() != OrderStatus.RESERVED) {
            // 已投件或已取消：本任务什么都不该做（重入的关键，不是"跳过报错"）
            log.info("超时释放跳过：当前状态 {}", order.getStatus());
            return false;
        }
        if (anyOpenSucceeded(order.getId())) {
            // 指令说门开过，而订单还停在待开门：这就是“崩在推进之前”的现场。
            // 不能退回可售：标待确认进台账，等一次人来确认（宁可少卖，不可卖错）。
            states.markContentUnverified(order.getSlotId(),
                    "待收敛回收：开门指令已成功但订单仍停在待开门，需现场确认柜内是否有物");
            log.warn("待收敛回收：门开过但状态未推进，改为标待确认而不会退回格口 orderNo={} slotId={}",
                    order.getOrderNo(), order.getSlotId());
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
 * {@code StorageOrderService.doorWatchTick} 里成一个短事务。
 */
