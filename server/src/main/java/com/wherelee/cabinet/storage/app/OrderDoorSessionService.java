package com.wherelee.cabinet.storage.app;

import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.device.api.CommandAction;
import com.wherelee.cabinet.device.api.CommandOutcome;
import com.wherelee.cabinet.device.api.CommandPort;
import com.wherelee.cabinet.device.api.CommandState;
import com.wherelee.cabinet.device.api.FaultType;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.OrderStatus;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import com.wherelee.cabinet.locker.api.CabinetSnapshot;
import com.wherelee.cabinet.locker.api.SlotAllocator;
import com.wherelee.cabinet.locker.api.SlotQueryPort;
import com.wherelee.cabinet.locker.api.SlotSwapPort;
import com.wherelee.cabinet.servicedesk.api.WorkOrderPort;
import com.wherelee.cabinet.storage.api.StorageOrderView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 开柜会话：三种开门动作（首次投件 / 中途取物 / 关门校验）的编排，以及预备开握手。
 *
 * <p>为什么从订单用例里拆出来：开门是一条“先问设备、再推状态、最后给话术”的长链，
 * 其中还夹着预备开重试→换格→退单的判决。它有自己的失败语义与配置（重试次数、间隔），
 * 与下单/取件/结算不是同一类判断。拆开后订单服务只负责“什么场景下该开哪扇门”。
 */
@Service
public class OrderDoorSessionService {

    private static final Logger log = LoggerFactory.getLogger(OrderDoorSessionService.class);

    private final OrderLookup lookup;
    private final OrderReleaseService release;
    private final BizStorageOrderMapper orderMapper;
    private final CommandPort deviceCommands;
    private final SlotQueryPort slotQueries;
    private final SlotSwapPort slotSwap;
    private final OrderDeviceOutcomeService deviceOutcomes;
    private final OrderVoucherService vouchers;
    private final WorkOrderPort workOrderPort;
    private final SlotAllocator allocator;

    /** 定-21 / 定-23：预备开在同一次点击内的自动重试次数与间隔。 */
    @Value("${cabinet.device.open-retries:2}")
    private long openRetries;

    @Value("${cabinet.device.open-retry-interval-ms:1000}")
    private long openRetryIntervalMs;

    public OrderDoorSessionService(OrderLookup lookup, OrderReleaseService release,
                                  BizStorageOrderMapper orderMapper,
                                  CommandPort deviceCommands, SlotQueryPort slotQueries,
                                  SlotSwapPort slotSwap, OrderDeviceOutcomeService deviceOutcomes,
                                  OrderVoucherService vouchers, WorkOrderPort workOrderPort,
                                  SlotAllocator allocator) {
        this.lookup = lookup;
        this.release = release;
        this.orderMapper = orderMapper;
        this.deviceCommands = deviceCommands;
        this.slotQueries = slotQueries;
        this.slotSwap = slotSwap;
        this.deviceOutcomes = deviceOutcomes;
        this.vouchers = vouchers;
        this.workOrderPort = workOrderPort;
        this.allocator = allocator;
    }

    public StorageOrderView openDoor(Long customerId, String orderNo, CommandAction action) {
        BizStorageOrder order = lookup.findOwned(customerId, orderNo);
        // 取件码连错锁定后，**开柜能力**被锁（不管首次投件还是中途取物）；
        // 但“关门校验”与“结束订单”不拦——结束不需要开门，把他困在柜子前不是本锁的目的。
        if (action != CommandAction.CLOSE_VERIFY) {
            vouchers.requireNotLocked(order);
        }
        CabinetSnapshot cabinet = slotQueries.cabinet(order.getCabinetId()).orElse(null);
        if (cabinet == null) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "柜机不存在，需人工核对 orderNo=" + orderNo);
        }

        boolean opening = action == CommandAction.OPEN;
        boolean tempOpen = action == CommandAction.OPEN_TEMP;
        boolean verifyClose = action == CommandAction.CLOSE_VERIFY;

        if (opening && order.getStatus() != OrderStatus.RESERVED) {
            throw new BizException(ResultCode.BIZ_ERROR, "当前状态不可开柜：" + order.getStatus());
        }
        if (tempOpen && order.getStatus() != OrderStatus.ACTIVE) {
            throw new BizException(ResultCode.BIZ_ERROR, "只有计费中的订单可以临时开柜");
        }
        if (verifyClose && !deviceOutcomes.mayCloseVerify(order)) {
            throw new BizException(ResultCode.BIZ_ERROR,
                    "没有待关闭的柜门（当前状态 " + order.getStatus() + "）");
        }

        if (opening) {
            // 定-23：先预备开（逻辑开，门不动）握手。只有柜机肯定应答了才往下走真开：
            // 把“不确定”消灭在还没发生物理动作之前，这一步失败时退钱是安全的。
            prepareHandshake(customerId, order, cabinet);
            // 握手过程中可能已换过格口：必须重读，不能拿着旧对象往下写（会撞 @Version）
            order = lookup.findOwned(customerId, orderNo);
        }

        if (opening) {
            order.transitTo(OrderStatus.OPENING);
            orderMapper.updateById(order);
        }

        long attempts = countAttempts(orderNo, action);
        String requestId = orderNo + ":" + action.name() + ":" + (attempts + 1);
        CommandOutcome outcome;
        try {
            outcome = deviceCommands.dispatch(cabinet.id(), order.getSlotId(), action, order.getId(), requestId);
        } catch (RuntimeException e) {
            // 上面已经把 RESERVED 推成 OPENING 并单独提交了（等回执不能开事务）。
            // 下发本身报错（柜机离线、通道异常）时若不退回，单就会卡在 OPENING：
            // 用户既重试不了，也没人来推它——这就是“每个异常路径都要回答下一步能做什么”。
            if (opening) {
                order.transitTo(OrderStatus.RESERVED);
                orderMapper.updateById(order);
            }
            throw e;
        }

        deviceOutcomes.apply(order, action, outcome);
        if (orderMapper.updateById(order) == 0) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "订单已被并发修改，请重试 orderNo=" + orderNo);
        }

        String openSlotNo = lookup.slotNoOf(order.getSlotId());
        // 首次投件开成功时把取件码交给用户（第 25 刀）：place 链用户看到的是这个视图，
        // 所以交付点必须在开柜动作之后，而不是在被 place 丢掉的 create 视图里。
        // OPEN_TEMP / CLOSE_VERIFY 也必须把设备真实结果带出来：AI 客服要据此判断“开了没有”，
        // 不能再拿 null 当“成功了”，也不能把“已下发无回执”说成“门已打开”。
        String note;
        if (opening && outcome.businessSuccess()) {
            note = vouchers.noteForNewOrder(order.getVoucherCode(), openSlotNo);
        } else if (outcome.message() != null && !outcome.message().isBlank()) {
            note = outcome.message();
        } else if (outcome.state() == CommandState.FAILED) {
            note = "设备明确返回失败，请人工检查柜门或柜机。";
        } else if (outcome.businessSuccess()) {
            note = switch (action) {
                case OPEN_TEMP -> "柜门已重新打开，请尽快完成取放并关好柜门。";
                case CLOSE_VERIFY -> "关门校验通过。";
                default -> "指令执行成功。";
            };
        } else {
            note = "指令已下发，但未收到柜机明确回执；请到柜前确认柜门是否弹开。";
        }
        return new StorageOrderView(order.getOrderNo(), cabinet.cabinetNo(), openSlotNo,
                order.getSizeType().name(), order.getStatus().name(), order.getEstimateMinutes(),
                0, allocator.strategy(), note);
    }

    /**
     * 预备开握手（定-23）：重试→换格→退单只可能发生在“柜机自己明确回报开不了”之后。
     *
     * <p>三种结果的处理完全不同（用户 2026-10-11 定）：
     * <ul>
     *   <li>PASSED：柜机确认能动作 → 往下走真开；</li>
     *   <li>SILENT（无回执/乱序旧回执）：“我们不知道”，<b>不动钱不动单</b>，直接往下走真开；</li>
     *   <li>FAILED（回执里柜机自己说开不了）：才能重试，耗尽后换格，换不到才退单退预收。</li>
     * </ul>
     *
     * <p>为什么真开不回话不能退单：柜机可能已经把门弹开了，只是回话丢了。这时退钱退单
     * 会把格子卖下一个人，而用户的包就在里面。
     */
    private void prepareHandshake(Long customerId, BizStorageOrder order, CabinetSnapshot cabinet) {
        int tries = 1 + (int) openRetries;
        for (int i = 0; i < tries; i++) {
            if (prepareOnce(order, cabinet) != PrepareResult.FAILED) {
                return;
            }
            if (i < tries - 1) {
                sleepBeforeRetry();
            }
        }

        // 重试用尽（柜机每次都明确回答“开不了”）：先试换同规模空格（定-21）
        BizStorageOrder current = lookup.findOwned(customerId, order.getOrderNo());
        if (swapToFreeSlot(current) && prepareOnce(current, cabinet) == PrepareResult.PASSED) {
            return;
        }

        // 换不到（或换完仍明确开不了）：此时门肯定没开过，退单退预收是安全的
        try {
            release.cancel(customerId, current.getOrderNo());
        } catch (RuntimeException releaseFailed) {
            log.error("预备开失败后的退单也没成功，由待收敛回收兜底 orderNo={}: {}",
                    current.getOrderNo(), releaseFailed.getMessage());
        }
        throw new BizException(ResultCode.BIZ_ERROR,
                "这个柜口现在打不开，也没有可用的同规模空格：已为你退单，预收全额退回。");
    }

    /** 预备开的三种结果：能开 / 开不了 / 没回话。“没回话”与“开不了”的处理完全不同。 */
    private enum PrepareResult { PASSED, FAILED, SILENT }

    /**
     * 一次预备开。
     *
     * <p>“确认坏了”只有一个判据：<b>{@code faultType == OPEN_FAILED}</b>（柜机自己回报开柜失败）。
     * 不能拿 {@code businessSuccess == false} 当判据：无回执、串位、离线、连续失败都不是死刑判决。
     */
    private PrepareResult prepareOnce(BizStorageOrder order, CabinetSnapshot cabinet) {
        long seq = countAttempts(order.getOrderNo(), CommandAction.PREPARE);
        var outcome = deviceCommands.dispatch(cabinet.id(), order.getSlotId(),
                CommandAction.PREPARE, order.getId(),
                order.getOrderNo() + ":PREPARE:" + (seq + 1));
        if (outcome.faultType() == FaultType.OPEN_FAILED) {
            return PrepareResult.FAILED;
        }
        if (outcome.businessSuccess()) {
            return PrepareResult.PASSED;
        }
        return PrepareResult.SILENT;
    }

    /**
     * 换同规模空格口，并把判定故障的旧格口摘出可分配池。
     *
     * <p>候选用的是 <b>同一条可分配判据</b>（空闲 ∧ 门已关 ∧ 无异常）：自己另写一套筛选就会出现
     * “换到一个有别人东西的格子”这种最坏结果。
     */
    private boolean swapToFreeSlot(BizStorageOrder order) {
        var swapped = slotSwap.swapIn(order.getCabinetId(), order.getSizeType(),
                order.getId(), order.getSlotId()).orElse(null);
        if (swapped == null) {
            return false;
        }
        Long oldSlotId = order.getSlotId();
        order.setSlotId(swapped.id());
        orderMapper.updateById(order);
        log.warn("预备开失败判定格口故障并换格 old={} new={} orderNo={}",
                oldSlotId, swapped.id(), order.getOrderNo());
        workOrderPort.openDeviceFault(order,
                true, "预备开自动重试 " + (1 + (int) openRetries) + " 次柜机均回报开不了，旧格口已摘出可分配池",
                "格口打不开，已改用格口 " + swapped.slotNo(), java.time.LocalDateTime.now().plusHours(2));
        return true;
    }

    /**
     * 重试前的间歇。<b>不在任何事务里</b>（openDoor 本身已为了等回执而跳出事务）：
     * 拿 sleep 占着连接就是第 9 刀压测的瓶颈。
     */
    private void sleepBeforeRetry() {
        try {
            Thread.sleep(openRetryIntervalMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ResultCode.SYSTEM_ERROR, "重试等待被中断，请重新发起开门");
        }
    }

    private long countAttempts(String orderNo, CommandAction action) {
        // “第几次尝试”是设备侧的账：由 CommandPort 按 requestId 前缀计数，订单侧不再查指令表。
        return deviceCommands.countAttempts(orderNo + ":" + action.name() + ":");
    }
}
