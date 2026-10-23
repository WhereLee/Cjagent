package com.wherelee.cabinet.storage.app;

import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.device.api.CommandAction;
import com.wherelee.cabinet.device.api.CommandOutcome;
import com.wherelee.cabinet.device.api.CommandPort;
import com.wherelee.cabinet.device.api.FaultType;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.OrderCloseReason;
import com.wherelee.cabinet.domain.enums.OrderStatus;
import com.wherelee.cabinet.locker.api.CabinetSnapshot;
import com.wherelee.cabinet.locker.api.SlotAllocator;
import com.wherelee.cabinet.locker.api.SlotPort;
import com.wherelee.cabinet.locker.api.SlotQueryPort;
import com.wherelee.cabinet.locker.api.SlotSnapshot;
import com.wherelee.cabinet.servicedesk.api.WorkOrderPort;
import com.wherelee.cabinet.storage.api.FundPort;
import com.wherelee.cabinet.storage.api.StorageOrderView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 取件与结束：算钱（短事务）与开门（事务外）两段，一个入口。
 *
 * <p>为什么拆出来：这条链的难点不在状态机，而在<b>事务边界</b>——下发开门要等设备回执最长 2.5 秒，
 * 把它塞进事务就是第 9 刀压测抓到的连接耗尽。拆成独立协作者后，“结算短事务 + 事务外开门”
 * 的顺序只有一个地方能改错，而不是散在订单用例的上千行里。
 */
@Service
public class OrderPickupFlowService {

    private static final Logger log = LoggerFactory.getLogger(OrderPickupFlowService.class);

    private final OrderLookup lookup;
    private final OrderVoucherService vouchers;
    private final FundPort funds;
    private final CommandPort deviceCommands;
    private final SlotQueryPort slotQueries;
    private final SlotPort states;
    private final WorkOrderPort workOrderPort;
    private final SlotAllocator allocator;
    private final TransactionTemplate newTx;

    public OrderPickupFlowService(OrderLookup lookup,
                                  OrderVoucherService vouchers,
                                  FundPort funds,
                                  CommandPort deviceCommands,
                                  SlotQueryPort slotQueries,
                                  SlotPort states,
                                  WorkOrderPort workOrderPort,
                                  SlotAllocator allocator,
                                  TransactionTemplate txTemplate) {
        this.lookup = lookup;
        this.vouchers = vouchers;
        this.funds = funds;
        this.deviceCommands = deviceCommands;
        this.slotQueries = slotQueries;
        this.states = states;
        this.workOrderPort = workOrderPort;
        this.allocator = allocator;
        this.newTx = new TransactionTemplate(txTemplate.getTransactionManager());
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 取件（用户当面结束）：按实际时长结算，开柜与取物紧跟着发生。
     *
     * <p>计费时长从 {@code started_at} 算到此刻，全部用服务端时间（S-07）。
     * 没落定过起点（容错期内就结束）就是 0 分钟——他确实还没占用过这个格子。
     */
    public StorageOrderView pickup(Long customerId, String orderNo) {
        // 登录态主路径不校验取件码（定-15：锁住的是“凭这张单开柜”，不是“把单结掉”）
        return settleAndOpen(customerId, orderNo, false, null);
    }

    /**
     * 取件（用户当面结束）：<b>算钱与开门是两件事，一内一外分开</b>。
     *
     * <p>结算（算钱 + 记事实）走一个短事务，提交后用<b>事务外</b>的一次开门把东西还给人。
     * 为什么不能一把垒在事务里：下发开门要等设备回执最长 2.5 秒，
     * 在事务里等外部设备就是第 9 刀压测抓出来的连接耗尽瓶颈（{@code openDoor} 为此早已跳出事务）。
     *
     * <p>定-17 说“不阻止结束与取物”，以前只做到了前半句：点了结束只算钱不开门，
     * 用户要把自己的东西拿走只能等后台强制开柜——这等于把“结束”做成了“放弃物品”（审计报告缺陷 1）。
     * 柜机上输码是手机没电时的第二条路径（定-6，定-25 把它做成双因子）。
     * 不填码时行为与登录态一致，不会因此拒任何合法用户；错码计数走独立事务，活过外层回滚。
     */
    public StorageOrderView pickup(Long customerId, String orderNo, String voucherCode) {
        return settleAndOpen(customerId, orderNo, true, voucherCode);
    }

    /**
     * 结算与取物开门的实现体。<b>事务边界就这一处</b>：结算走短事务，开门在事务外。
     *
     * @param withVoucher 是否走“凭码”那条路（只有带了码才校码）
     */
    private StorageOrderView settleAndOpen(Long customerId, String orderNo,
                                           boolean withVoucher, String voucherCode) {
        StorageOrderView settled = newTx.execute(status -> {
            BizStorageOrder order = lookup.findOwned(customerId, orderNo);
            if (withVoucher) {
                vouchers.verify(order, voucherCode);
            }
            return finish(customerId, orderNo, OrderCloseReason.NORMAL);
        });
        return openForPickup(customerId, orderNo, settled);
    }

    /**
     * 结算之后把门打开，让用户取走自己的东西（定-17 后半句，缺陷 1 与缺陷 2 的同一个出口）。
     *
     * <p><b>失败不回滚、也不换格</b>：钱已算完、单已结束，换格等于凭空造一张新订单（没人授权过）。
     * 这时只做三件事：留下日志、复用定-23 已定的设备故障工单机制（不新增任何类型或阈值）、
     * 把“现在该找谁”写进返回话里。
     *
     * <p>为什么门开了就不会被卖：这一格的 {@code door_open_at} 有值，而可分配判据要求门已关，
     * 取物窗口内不需要额外的锁或状态。
     */
    private StorageOrderView openForPickup(Long customerId, String orderNo, StorageOrderView settled) {
        BizStorageOrder order = lookup.findOwned(customerId, orderNo);
        CabinetSnapshot cabinet = slotQueries.cabinet(order.getCabinetId()).orElse(null);
        SlotSnapshot slot = slotQueries.slot(order.getSlotId()).orElse(null);
        if (cabinet == null || slot == null) {
            log.error("结算后取物开门缺少柜机或格口信息 orderNo={} cabinetId={} slotId={}",
                    orderNo, order.getCabinetId(), order.getSlotId());
            // 门没开成就不能留下“FREE 且无异常”的空位：那会把还放着东西的格子卖给下一位
            states.markContentUnverified(order.getSlotId(),
                    "结算后无法下发取物开门（柜机或格口信息缺失），物品仍在柜内，需现场确认");
            return settled.withMessage("柜机信息不完整，门未打开：请找现场工作人员协助取物");
        }

        if (order.getVoucherLockedAt() != null) {
            // 凭码锁定：单照结（钱已在事务里算完），但不开门——锁的就是“凭这张单开柜”；
            // 把他困在柜前不是本锁的目的，找客服核验才是出路
            states.markContentUnverified(order.getSlotId(),
                    "结算后因取件码连错锁定而未能开门取物，物品仍在柜内，需客服核验后开门");
            return settled.withMessage("本单取件码连续输错已被锁定，柜门未打开："
                    + "可在订单里重置取件码解锁后取物，或联系客服核验");
        }

        long attempts = countAttempts(orderNo, CommandAction.OPEN);
        CommandOutcome outcome;
        try {
            outcome = deviceCommands.dispatch(cabinet.id(), order.getSlotId(),
                    CommandAction.OPEN, order.getId(),
                    orderNo + ":PICKUP:" + (attempts + 1));
        } catch (RuntimeException offline) {
            // 柜机离线/通道异常：从 dispatch 直接抛出来，不在这里吃掉（吃掉就把“整柜离线”好成了“格口坏了”）
            log.error("结算后取物开门下发失败 orderNo={}: {}", orderNo, offline.getMessage());
            states.markContentUnverified(order.getSlotId(),
                    "结算后取物开门下发失败（柜机离线或通道异常），物品仍在柜内，需现场开门");
            workOrderPort.openDeviceFault(order, true,
                    "用户已结束订单但取物开门下发失败（柜机离线或通道异常），需现场协助开门",
                    "取物开门无响应，已标格口异常", LocalDateTime.now().plusMinutes(10));
            return settled.withMessage("柜机现在连不上，门没打开：已通知现场人员，请联系客服或现场工作人员");
        }

        if (outcome.faultType() == FaultType.OPEN_FAILED) {
            // 柜机自己说开不了：东西还在里面而人拿不到。这时最危的不是“他拿不到”，
            // 而是这一格在系统里已经回 FREE——下一位会被分到同一个开着不了、里面却有东西的格子。
            // markFaulty 拓不了这个情：它的条件限定只摘 RESERVED/OCCUPIED 的格（不抢别人已分走的），
            // 而可分配判据里的 anomaly IS NULL 正好能挡住——拿不到“柜内已空”凭据、必须人工到场，
            // 这就是 CONTENT_UNVERIFIED 本来的语义，不是新造的异常类型。
            log.warn("结算后取物开门被柜机明确拒绝 orderNo={}", orderNo);
            states.markContentUnverified(order.getSlotId(),
                    "用户已结束订单但柜机明确回报打不开门：物品仍在柜内，需现场开门核验");
            workOrderPort.openDeviceFault(order, true,
                    "用户已结束订单但柜机明确回报打不开门，物品仍在柜内",
                    "取物开门失败，需现场开门", LocalDateTime.now().plusMinutes(10));
            return settled.withMessage("这个柜口打不开：已通知现场人员处理，请联系客服或现场工作人员");
        }

        // 门开了（或柜机应答正常但未回明细）：登记门态与证据，取物期间这一格不会被分给别人。
        // 不再在这里顺手标“门未关”台账：door_open_at 本身就是超时回扫的输入，
        // 而“结束后门一直没关”属于取完东西不收尾的现场，由回扫与对账接手，不在这里刷一张单
        states.afterDoorOpened(slot.id(), customerId);
        return settled.withMessage(outcome.businessSuccess()
                ? "柜门已打开，请取走物品并随手关好柜门"
                : "已下发开门，请确认柜门已开；若没反应请联系现场工作人员");
    }

    /**
     * 结束订单（19A，2026-10-11 定稿）：<b>用户点了就结束、就收费、就取物，一律不拦</b>。
     *
     * <p>原来这一而三段写了三道闸（门必须关、柜内必须无物、现场与远程凭据不一致），
     * 它们的连带代价是“用户被自己的柜门困住”——可物品是用户的，钱不是。
     * 新口径把这些判据从**拦人降级成记事实**：门没关、物检报有东西，都不阻止结束与取物，
     * 只留下证据与异常，后面的 AI 标签与工单流转（19C/19D）接着处理它。
     */
    private StorageOrderView finish(Long customerId, String orderNo, OrderCloseReason reason) {
        BizStorageOrder order = lookup.findOwned(customerId, orderNo);
        if (order.getStatus() == OrderStatus.CLOSED || order.getStatus() == OrderStatus.CANCELLED) {
            throw new BizException(ResultCode.BIZ_ERROR, "这单已经结束了：" + order.getStatus());
        }
        if (order.getStatus() == OrderStatus.RESERVED) {
            // 还没开过柜：没占用过这一格，走取消路径（预收整笔退回），不该混进结算
            throw new BizException(ResultCode.BIZ_ERROR, "柜门还没打开过，无需结束：请直接取消防错");
        }
        // 定-17 之后这里**不再读柜内**（2026-10-12 用户点破：“点结束直接开门不就好了，读它做什么”）。
        // 那次读数是已作废的“结束三条件”残留：它发生在开门取物**之前**，而此刻柜内必然还放着他
        // 自己的东西——读到“有物”是常态，旧代码却把它当成“他遗弃了物品”标成 CONTENT_LEFT，
        // 于是每一次正常取物都会锁住一格等人工清。遗留与否只能在“门关上之后”判，
        // 见 CompartmentStateService#afterDoorClosed。
        // 2026-10-12：误报免除作废——不存在“把计费终点回拨到争议起始时刻”这回事，
        // 结束从来只算到此刻
        LocalDateTime settleEnd = LocalDateTime.now();
        long actualMinutes = order.getStartedAt() == null ? 0L
                : Math.max(0L, Duration.between(order.getStartedAt(), settleEnd).toMinutes());
        funds.settle(order, actualMinutes, reason);

        CabinetSnapshot cabinet = slotQueries.cabinet(order.getCabinetId()).orElse(null);
        return new StorageOrderView(order.getOrderNo(), cabinet == null ? "-" : cabinet.cabinetNo(),
                lookup.slotNoOf(order.getSlotId()), order.getSizeType().name(), order.getStatus().name(),
                0, allocator.strategy(), null);
    }

    private long countAttempts(String orderNo, CommandAction action) {
        // “第几次尝试”是设备侧的账：由 CommandPort 按 requestId 前缀计数，订单侧不再查指令表。
        return deviceCommands.countAttempts(orderNo + ":" + action.name() + ":");
    }
}
