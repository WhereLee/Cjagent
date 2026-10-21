package com.wherelee.cabinet.storage.app;
import com.wherelee.cabinet.locker.api.CabinetSnapshot;
import com.wherelee.cabinet.locker.api.SlotAllocator;
import com.wherelee.cabinet.locker.api.Sensing;
import com.wherelee.cabinet.locker.api.SlotPoolPort;
import com.wherelee.cabinet.locker.api.SlotPort;
import com.wherelee.cabinet.locker.api.SlotQueryPort;
import com.wherelee.cabinet.locker.api.SlotSnapshot;
import com.wherelee.cabinet.locker.api.SlotSwapPort;
import com.wherelee.cabinet.locker.api.Presence;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.storage.api.CreateOrderCommand;
import com.wherelee.cabinet.storage.api.StorageOrderView;
import com.wherelee.cabinet.storage.api.VoucherView;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.locker.api.CabinetStatus;
import com.wherelee.cabinet.domain.enums.OrderCloseReason;
import com.wherelee.cabinet.domain.enums.OrderStatus;
import com.wherelee.cabinet.locker.api.SizeType;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 寄存单用例：占位（create）与取消（cancel）。
 *
 * <p><b>为什么先预生成订单 ID 再占格口</b>：占用要把 {@code current_order_id} 写到格口上，
 * 而订单还没插就没有 ID。用雪花 ID 预生成（{@link IdWorker#getId()}）可以消除
 * "先插订单→再占格口→失败要删订单"这种补偿链。<b>顺序是：占格 → 插单</b>，
 * 中间任何一步抛异常都由事务整体回滚，不会留下"占了位没有单"的孤儿。
 *
 * <p>事务边界只在这一层：分配器不开事务（它必须能被塞进更大的用例里组合），
 * Controller 也不开。
 *
 * <p>本刀只做"占位—取消"这一段。<b>计费、押金、开柜指令、超时释放都在后面几刀</b>，
 * 所以 pricing_snapshot 现在是占位 JSON——留字段而不是等到时用 ALTER 补，
 * 是为了让"快照"这个概念从第一版就存在（改结构比改语义容易得多）。
 */
@Service
public class StorageOrderService {

    private static final Logger log = LoggerFactory.getLogger(StorageOrderService.class);

    /** 格口/柜机/点位的只读查询：订单不直接读 locker 的表。 */
    private final SlotQueryPort slotQueries;
    /** 空闲集合回写：格口空出来后由 locker 维护可分配集合。 */
    private final SlotPoolPort slotPool;
    private final BizStorageOrderMapper orderMapper;
    private final SlotAllocator allocator;
    /** 设备明确回报打不开时换同尺寸空格（候选与预占都在 locker 内）。 */
    private final SlotSwapPort slotSwap;
    /** 取件凭据：发码、验码、错次计数与锁定、重置。 */
    private final OrderVoucherService vouchers;
    /** 设备结果 → 订单状态、关门收敛与计费起点。 */
    private final OrderDeviceOutcomeService deviceOutcomes;
    private final com.wherelee.cabinet.device.api.CommandPort deviceCommands;
    private final com.wherelee.cabinet.storage.api.FundPort funds;
    /** 门态/物检/异常的唯一写入口（第 13B 刀）。 */
    private final SlotPort states;
    /** 定-21/定-23：预备开重试无效时要开一条设备故障工单（不然故障只活在日志里）。 */
    private final com.wherelee.cabinet.servicedesk.api.WorkOrderPort workOrderPort;
    // 柜内物品争议与“误报免除”已于 2026-10-12 作废（用户定：结束不拦人后“争议期间”不存在）。
    /** 欠费合计的只读查询（与后台展示同一口径，避免“后台说没欠、下单却被拒”）。 */
    private final com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper arrearsMapper;
    /** 取件凭据的站内消息（第 25 刀）：下单发一条，重置改写同一条。 */
    private final com.wherelee.cabinet.notify.api.NoticePort notices;
    /**
     * 取件码错次与锁定要用**独立事务**写：错码后要抛异常，外层事务会回滚，
     * 同一事务里写的计数与锁定会跟着消失——结果是“永远数不到第五次，永远锁不上”却看不出来。
     * 与 13C 的 ItemDisputeService#markBlocked 同一个理由。
     */
    private final org.springframework.transaction.support.TransactionTemplate newTx;
    /**
     * 调度器延迟拿取：直接注入会形成循环依赖
     * （StorageOrderService → DelayTaskService → SlotReleaseHandler → StorageOrderService）。
     * 用 ObjectProvider 把解析推迟到第一次使用时，而不是用 @Lazy 把设计问题遮起来：
     * 这个环本身是有意的（超时释放就是取消），但构造期不能互相依赖。
     */
    private final org.springframework.beans.factory.ObjectProvider<com.wherelee.cabinet.application.task.DelayTaskService> taskScheduler;

    /**
     * 待收敛回收的阈值（定-4，用户 2026-10-10 定的 10 分钟）。
     *
     * <p>它不再是“给用户留的开柜宽限”（那个概念随预占一起删了）：下单与开门已合并成一步，
     * 正常路径不会停在“待开门”。这条任务只兜“崩在占格与开门之间”的残留，所定得宽一些无所谓，
     * 定短了反而会误伤柜机前翻包的人。
     */
    @org.springframework.beans.factory.annotation.Value("${cabinet.scheduler.reconcile-minutes:10}")
    private long reconcileMinutes;

    /**
     * 定-21 / 定-23（用户选 A）：预备开在同一次点击内的自动重试次数与间隔。
     * “立即重试”既不是无限重试（会把一台柜机反复摇），也不能一次不成就判死。
     */
    @org.springframework.beans.factory.annotation.Value("${cabinet.device.open-retries:2}")
    private long openRetries;

    @org.springframework.beans.factory.annotation.Value("${cabinet.device.open-retry-interval-ms:1000}")
    private long openRetryIntervalMs;

    public StorageOrderService(SlotQueryPort slotQueries,
                               SlotPoolPort slotPool,
                               BizStorageOrderMapper orderMapper,
                               SlotAllocator allocator,
                               SlotSwapPort slotSwap,
                               OrderVoucherService vouchers,
                               OrderDeviceOutcomeService deviceOutcomes,
                               com.wherelee.cabinet.device.api.CommandPort deviceCommands,
                               com.wherelee.cabinet.storage.api.FundPort funds,
                               SlotPort states,
                               com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper arrearsMapper,
                               org.springframework.transaction.support.TransactionTemplate txTemplate,
                               org.springframework.beans.factory.ObjectProvider<com.wherelee.cabinet.application.task.DelayTaskService> taskScheduler,
                               com.wherelee.cabinet.servicedesk.api.WorkOrderPort workOrderPort,
                               com.wherelee.cabinet.notify.api.NoticePort notices) {
        this.slotQueries = slotQueries;
        this.slotPool = slotPool;
        this.orderMapper = orderMapper;
        this.allocator = allocator;
        this.slotSwap = slotSwap;
        this.vouchers = vouchers;
        this.deviceOutcomes = deviceOutcomes;
        this.deviceCommands = deviceCommands;
        this.funds = funds;
        this.states = states;
        this.workOrderPort = workOrderPort;
        this.notices = notices;
        this.arrearsMapper = arrearsMapper;
        this.newTx = new org.springframework.transaction.support.TransactionTemplate(
                txTemplate.getTransactionManager());
        this.newTx.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.taskScheduler = taskScheduler;
    }

    @Transactional
    public StorageOrderView create(Long customerId, CreateOrderCommand command) {
        CabinetSnapshot cabinet = slotQueries.cabinetByNo(command.cabinetNo()).orElse(null);
        if (cabinet == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "柜机不存在：" + command.cabinetNo());
        }
        // 停用/故障的柜机不接受新寄存；但"离线"不在这里挡——离线仍可取件（降级方向见 S-09）
        if (!CabinetStatus.ENABLED.name().equals(cabinet.cabinetStatus())) {
            throw new BizException(ResultCode.BIZ_ERROR, "该柜机暂停服务，请选择邻近柜机");
        }

        SizeType required = parseSize(command.sizeType());
        // 欠费拦截：**欠费 > 0 就不得再下单，没有阈值**（第 12 刀只做了“只记不锁件”，
        // 拦下一环始终没落，所以始终“欠着钱还能一直下单”）。它不靠前端置灰：
        // 那只是个提示，绕过一个 POST 就能白用。
        // 取件仍绝不拦（欠的是债不是留置权），只拦住“新用户服务”这个入口。
        long owing = arrearsMapper.sumCustomerArrears(customerId);
        if (owing > 0L) {
            throw new BizException(ResultCode.BIZ_ERROR,
                    "您有未缴清的费用（" + owing + " 点），请先在点数页补缴后再下单");
        }
        // 一人同时只能占一个格口（2026-10-10 定-5）。查 active_flag 而不是状态列表：
        // 这个列就是“这张单还活动着”的结构化定义（防超卖唯一索引用的也是它），
        // 拿状态枚举去 notIn 终态会随状态机演进而漏判。
        long activeOrders = orderMapper.selectCount(Wrappers.<BizStorageOrder>lambdaQuery()
                .eq(BizStorageOrder::getCustomerId, customerId)
                .eq(BizStorageOrder::getActiveFlag, 1));
        if (activeOrders > 0) {
            throw new BizException(ResultCode.BIZ_ERROR,
                    "你还有未结束的寄存单：同一时间只能用一个格口，请先取件结束再开新单");
        }
        Long orderId = IdWorker.getId();
        SlotAllocator.AllocatedSlot allocated = allocator.allocate(cabinet.id(), required, orderId);

        BizStorageOrder order = new BizStorageOrder();
        order.setId(orderId);
        order.setOrderNo("SO" + orderId);
        order.setSiteId(cabinet.siteId());
        order.setCabinetId(cabinet.id());
        order.setSlotId(allocated.slotId());
        order.setSizeType(allocated.actualSize());
        order.setCustomerId(customerId);
        order.setVoucherCode(VoucherCodes.newVoucher());
        // C 定案：用户不再选预估时长。这里**故意落 0 而不是客户端传来的值**，
        // 免得以后的代码拿它当“用户许下的时长”做依据（字段本身已标废弃，待下一批清理）
        order.setEstimateMinutes(0);
        order.setTempOpenCount(0);
        order.setDepositPoints(0L);
        order.setFrozenPoints(0L);
        order.setSettledPoints(0L);
        order.setArrearsPoints(0L);
        order.initStatus();
        order.transitTo(OrderStatus.RESERVED);

        try {
            // 先算钱再插单：holdFunds 只往订单对象上写账户押金与参数快照，一次 insert 就带着它们落库。
            // 之前先 insert 再补一次 updateById，因为 BizStorageOrder 带 @Version，
            // 那次 update 影响 0 行却被忽略，定价快照静默丢失（结算时才发现）——
            // 顺序改对比“记住检查每个 updateById”更可靠。
            funds.holdFunds(order);
            vouchers.insert(order);
        } catch (RuntimeException e) {
            // 罕见但必须留痕：格口已绑单而订单没落库。事务会回滚掉占用，
            // 但如果不打日志，压测时看到的就是"莫名失败"而查不到根因
            log.error("订单落库失败，格口占用将随事务回滚 orderId={} slotId={} strategy={}",
                    orderId, allocated.slotId(), allocator.strategy(), e);
            throw e;
        }

        // 超时释放任务：它与下单**同一个事务**（本方法标了 @Transactional），所以不存在
        // “单已落库但没人看”的窗口：要一起成，要一起不成。提醒（Redis/MQ）是 best-effort，
        // 丢了只影响延迟不影响正确性（执行权在任务表上的租约条件更新）。
        var scheduler = taskScheduler.getIfAvailable();
        if (scheduler != null) {
            scheduler.schedule(com.wherelee.cabinet.domain.enums.TaskType.SLOT_RELEASE, order.getOrderNo(),
                    order.getTenantId(), LocalDateTime.now().plusMinutes(reconcileMinutes));
            // 押金退还任务不在这里登记：此时押金“挂着”是正常态，提前登记只会每张单都进
            // “未终态→退避重试→判死”的循环，把 DEAD 变成噪声。真正需要它的是对账发现悬挂押金后
            // 现场登记（见 LedgerReconcileHandler）
        }

        // 取件码交付（第 25 刀）：下单时点发一条可回看的站内凭证（与下单同事务，单没成就没消息）。
        // 码在此之前只活在数据库里——校验、计数、锁定全都有，就是没人能把码交给用户，
        // 柜机屏那条凭码路径事实上不可达（"有锁没钥匙"）。
        String createdSlotNo = slotNoOf(allocated.slotId());
        notices.emit(cabinet.tenantId(),
                com.wherelee.cabinet.notify.api.NoticePort.CHANNEL_MESSAGE,
                "PICKUP_VOUCHER", order.getOrderNo() + ":voucher",
                vouchers.noteForExisting(order.getVoucherCode(), createdSlotNo),
                order.getCustomerId(), null, order.getOrderNo());
        return new StorageOrderView(order.getOrderNo(), cabinet.cabinetNo(), createdSlotNo,
                allocated.actualSize().name(), order.getStatus().name(), order.getEstimateMinutes(),
                allocated.triedSlots(), allocator.strategy(),
                vouchers.noteForNewOrder(order.getVoucherCode(), createdSlotNo));
    }

    @Transactional
    public StorageOrderView cancel(Long customerId, String orderNo) {
        BizStorageOrder order = orderMapper.selectOne(Wrappers.<BizStorageOrder>lambdaQuery()
                .eq(BizStorageOrder::getOrderNo, orderNo));
        if (order == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "寄存单不存在");
        }
        // 越权访问不区分"不存在"与"不是你的"：同一句提示，避免用 404/403 差值枚举他人单号
        if (!order.getCustomerId().equals(customerId)) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "寄存单不存在");
        }

        // 取消不能比结束更松：检测到柜内有东西时不得取消。否则就是“钱退了、件留下了、
        // 格子又卖给了第三个人”——那是一整个流程里最难收拾的现场（不变量 I12）。
        Sensing sensing = states.sense(order.getCabinetId(), order.getSlotId());
        if (sensing.presence() == com.wherelee.cabinet.locker.api.Presence.PRESENT) {
            throw new BizException(ResultCode.BIZ_ERROR,
                    "柜内检测到物品：请先取出再取消（或走“声明放弃物品”结束）");
        }

        if (order.getToleranceUntil() != null && !emptyEvidence(sensing)) {
            // 门曾经开过（他可能已经放了东西进去）而柜内又确认不了：“取消”不能比“结束”拿到更少的凭据。
            // 不拦他取消（未计费阶段拦人无意义），但格口锁成待确认，等一次人来看看
            states.markContentUnverified(order.getSlotId(),
                    "开过门的单被取消且柜内无法确认，需现场确认后才能重新分配");
        }
        return releaseOrder(order);
    }

    /**
     * 把一张不再计费的单释放掉：转 CANCELLED + 释放格口 + 全额解冻 + 同步预扣集合。
     *
     * <p>两个入口共用它：<b>用户主动取消</b>与<b>上报遗留物后退单</b>。两者对柜内的态度相反
     * （前者“有物就拦”，后者正因为看到有物才上报），所以柜内检查留在 cancel() 里而不是下沉到这里——
     * 下沉了会把上报路径堵死，不下沉则会漏拦取消路径。
     */
    private StorageOrderView releaseOrder(BizStorageOrder order) {
        order.transitTo(OrderStatus.CANCELLED);
        boolean released = states.releaseReserved(order.getSlotId(), order.getId());
        if (!released) {
            // 状态说该释放却释放不掉：说明格口已被他人改写，必须报错回滚而不是"取消成功但格口仍占着"
            throw new BizException(ResultCode.SYSTEM_ERROR, "格口状态与订单不一致，请联系运营处理");
        }
        funds.cancelHold(order);
        if (orderMapper.updateById(order) == 0) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "订单已被并发修改，请重试 orderNo=" + order.getOrderNo());
        }
        // 预扣策略下 DB 回 FREE 了就必须让 Redis 空闲集合也看到它，否则这个位置从此“谁也算不到”。
        // 失败只影响准入精度（少卖），不影响正确性，所以这里只告警不阻断取消。
        if ("prealloc".equals(allocator.strategy())) {
            try {
                slotPool.addFree(order.getCabinetId(), order.getSizeType(), order.getSlotId());
            } catch (RuntimeException e) {
                log.warn("格口已回 FREE 但 Redis 空闲集合同步失败（等校准修复）slotId={}", order.getSlotId(), e);
            }
        }
        return new StorageOrderView(order.getOrderNo(), cabinetNoOf(order.getCabinetId()),
                slotNoOf(order.getSlotId()), order.getSizeType().name(), order.getStatus().name(),
                order.getEstimateMinutes(), 0, allocator.strategy(), null);
    }

    private SizeType parseSize(String value) {
        try {
            return SizeType.of(value);
        } catch (IllegalArgumentException e) {
            throw new BizException(ResultCode.PARAM_INVALID, "尺寸不合法：" + value);
        }
    }

    private String slotNoOf(Long slotId) {
        return slotQueries.slot(slotId).map(SlotSnapshot::slotNo).orElse("-");
    }

    private String cabinetNoOf(Long cabinetId) {
        return slotQueries.cabinet(cabinetId).map(CabinetSnapshot::cabinetNo).orElse("-");
    }

    /** 供测试与指标读取当前生效策略。 */
    public String activeStrategy() {
        return allocator.strategy();
    }

    /** 订单行是否已存在（异步路径的“创建中”判定靠它，不区分归属）。 */
    public boolean orderExists(String orderNo) {
        return orderMapper.exists(Wrappers.<BizStorageOrder>lambdaQuery()
                .eq(BizStorageOrder::getOrderNo, orderNo));
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
            BizStorageOrder order = findOwned(customerId, orderNo);
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
        BizStorageOrder order = findOwned(customerId, orderNo);
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

        long attempts = countAttempts(orderNo, com.wherelee.cabinet.device.api.CommandAction.OPEN);
        com.wherelee.cabinet.device.api.CommandOutcome outcome;
        try {
            outcome = deviceCommands.dispatch(cabinet.id(), order.getSlotId(),
                    com.wherelee.cabinet.device.api.CommandAction.OPEN, order.getId(),
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

        if (outcome.faultType() == com.wherelee.cabinet.device.api.FaultType.OPEN_FAILED) {
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

    // abandon（声明放弃物品）与 remoteClose（远程结束）已于 2026-10-12 删除：
    // 前者被定-13（结束不拦人）抽掉——柜里有东西也照样结束并自动标遗留物，不需要一个“放弃”按钮；
    // 后者被定-15 抽掉——结束本来就是在手机上点的，“远程”不再是一种需要加收的情形。
    // 两个关单原因枚举值（ABANDONED / REMOTE）保留：历史单上写着它们，删了旧单读不出自己是怎么关的。

    /**
     * 结束订单的共用收口（今天只剩“用户点结束”一个入口：当面与远程已合一）。
     *
     * <p>写成一处而不是三份：分开写过几天就会出现“当面结束会退钱、另一种结束漏了退”这种
     * 只在一半路径上修的 bug（第 12 刀已经为退款路径合并过一次）。
     */
    /**
     * 结束订单（19A，2026-10-11 定稿）：<b>用户点了就结束、就收费、就取物，一律不拦</b>。
     *
     * <p>原来这一而三段写了三道闸（门必须关、柜内必须无物、现场与远程凭据不一致），
     * 它们的连带代价是“用户被自己的柜门困住”——可物品是用户的，钱不是。
     * 新口径把这些判据从**拦人降级成记事实**：门没关、物检报有东西，都不阻止结束与取物，
     * 只留下证据与异常，后面的 AI 标签与工单流转（19C/19D）接着处理它。
     */
    private StorageOrderView finish(Long customerId, String orderNo, OrderCloseReason reason) {
        BizStorageOrder order = findOwned(customerId, orderNo);
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
                slotNoOf(order.getSlotId()), order.getSizeType().name(), order.getStatus().name(),
                order.getEstimateMinutes(), 0, allocator.strategy(), null);
    }

    /**
     * 开柜类动作（首次投件 OPEN / 中途取物 OPEN_TEMP / 关门校验 CLOSE_VERIFY）。
     *
     * <p><b>requestId 按“这是该单该动作的第几次”生成</b>：只写 {@code orderNo:action} 会错——
     * 第一次开柜失败后用户再点，会命中幂等而拿回“失败”旧结果，重试通道被自己堵死；
     * 完全随机又会让“双击”产生命令两次下发。所以：同一次尝试幂等，重试换新键。
     *
     * <p>注意本方法<b>不在事务里</b>：等回执最长 2.5 秒，开事务会占住连接（第 9 刀压测的瓶颈）。
     * 指令与回执的写入各自成一个短事务（在 DeviceCommandService 内部），
     * 这里的订单状态推进是第 3 个短事务。<b>失败后果说清：中途崩溃会留下“设备开了门但订单没变”的现场，
     * 由上报流水与超时回扫收敛（第 13 刀）。</b>
     */
    public StorageOrderView openDoor(Long customerId, String orderNo,
                                     com.wherelee.cabinet.device.api.CommandAction action) {
        BizStorageOrder order = findOwned(customerId, orderNo);
        // 取件码连错锁定后，**开柜能力**被锁（不管首次投件还是中途取物）；
        // 但“关门校验”与“结束订单”不拦——结束不需要开门，把他困在柜子前不是本锁的目的。
        if (action != com.wherelee.cabinet.device.api.CommandAction.CLOSE_VERIFY) {
            vouchers.requireNotLocked(order);
        }
        CabinetSnapshot cabinet = slotQueries.cabinet(order.getCabinetId()).orElse(null);
        if (cabinet == null) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "柜机不存在，需人工核对 orderNo=" + orderNo);
        }

        boolean opening = action == com.wherelee.cabinet.device.api.CommandAction.OPEN;
        boolean tempOpen = action == com.wherelee.cabinet.device.api.CommandAction.OPEN_TEMP;
        boolean verifyClose = action == com.wherelee.cabinet.device.api.CommandAction.CLOSE_VERIFY;

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
            order = findOwned(customerId, orderNo);
        }

        if (opening) {
            order.transitTo(OrderStatus.OPENING);
            orderMapper.updateById(order);
        }

        long attempts = countAttempts(orderNo, action);
        String requestId = orderNo + ":" + action.name() + ":" + (attempts + 1);
        com.wherelee.cabinet.device.api.CommandOutcome outcome;
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

        String openSlotNo = slotNoOf(order.getSlotId());
        // 首次投件开成功时把取件码交给用户（第 25 刀）：place 链用户看到的是这个视图，
        // 所以交付点必须在开柜动作之后，而不是在被 place 丢掉的 create 视图里。
        // OPEN_TEMP / CLOSE_VERIFY 也必须把设备真实结果带出来：AI 客服要据此判断“开了没有”，
        // 不能再拿 null 当“成功了”，也不能把“已下发无回执”说成“门已打开”。
        String note;
        if (opening && outcome.businessSuccess()) {
            note = vouchers.noteForNewOrder(order.getVoucherCode(), openSlotNo);
        } else if (outcome.message() != null && !outcome.message().isBlank()) {
            note = outcome.message();
        } else if (outcome.state() == com.wherelee.cabinet.device.api.CommandState.FAILED) {
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
     * 重置取件码（第 25 刀，用户 2026-10-14 拍板）：本人登录态的凭据自救与锁定出路。
     *
     * <p>换码、错次清零、**解除锁定**三件事一起做：锁定防的是猜码的路人，而重置要求登录态
     * （本人设备），身份已由 token 证明，所以"自助重置即解锁"不伤害锁定原本要防的事。
     *
     * <p>写 null 必须走显式 UPDATE：{@code voucher_locked_at} 没有 ALWAYS 注解，
     * 用 updateById 时 null 会被字段策略吞掉——**锁定解不掉却看不出来**。乐观锁手动带
     * version 条件（与 @Version 同一张判据）；撞码只重"换码"这一步，理由同 insertWithFreshVoucher。
     */
    @Transactional
    public VoucherView resetVoucher(Long customerId, String orderNo) {
        BizStorageOrder order = findOwned(customerId, orderNo);
        if (order.getStatus().isTerminal()) {
            throw new BizException(ResultCode.BIZ_ERROR, "该单已结束，取件码已失效，无需重置");
        }
        String finalCode = vouchers.rotate(order);
        // 一条凭证消息始终反映当前码：改写而不是再发一条（消息页堆废码比没码更糟）
        notices.rewrite(orderNo + ":voucher", vouchers.noteForExisting(finalCode, slotNoOf(order.getSlotId())));
        return new VoucherView(orderNo, cabinetNoOf(order.getCabinetId()), slotNoOf(order.getSlotId()),
                order.getSizeType().name(), order.getStatus().name(), finalCode, false);
    }

    /**
     * 预备开握手（定-23）：重试→换格→退单只可能发生在“柜机自己明确回报开不了”之后。
     *
     * <p>三种结果的处理完全不同（用户 2026-10-11 定）：
     * <ul>
     *   <li>PASSED：柜机确认能动作 → 往下走真开；</li>
     *   <li>SILENT（无回执/乱序旧回执）：“我们不知道”，<b>不动钱不动单</b>，直接往下走真开
     *       （= 定-23 之前的行为），由回扫与 10 分钟待收敛回收收口（定-4）；</li>
     *   <li>FAILED（回执里柜机自己说开不了）：才能重试，耗尽后换格，换不到才退单退预收。</li>
     * </ul>
     *
     * <p>为什么真开不回话不能退单：柜机可能已经把门弹开了，只是回话丢了。这时退钱退单
     * 会把格子卖下一个人，而老张的包就在里面。
     *
     * <p>柜机离线与通道异常从 {@code dispatch} 直接抛出，不在这里吃掉：吃掉它就等于把
     * “整柜离线”好成了“这个格口坏了”，后面的换格与退单都是错的判决，也会丢掉 50100 这个已有契约。
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
        BizStorageOrder current = findOwned(customerId, order.getOrderNo());
        if (swapToFreeSlot(current) && prepareOnce(current, cabinet) == PrepareResult.PASSED) {
            return;
        }

        // 换不到（或换完仍明确开不了）：此时门肯定没开过，退单退预收是安全的
        try {
            cancel(customerId, current.getOrderNo());
        } catch (RuntimeException release) {
            log.error("预备开失败后的退单也没成功，由待收敛回收兜底 orderNo={}: {}",
                    current.getOrderNo(), release.getMessage());
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
     * 不能拿 {@code businessSuccess == false} 当判据：无回执（NO_REPORT）、串位（TARGET_MISMATCH）、
     * 离线（OFFLINE）、连续失败（CONSECUTIVE_FAIL）都不是对这个格口的死刑判决，
     * 把它们当“坏了”就会误退单、误判故障（本轮实测：一次就把 4 条既有设备用例打红）。
     */
    private PrepareResult prepareOnce(BizStorageOrder order, CabinetSnapshot cabinet) {
        long seq = countAttempts(order.getOrderNo(), com.wherelee.cabinet.device.api.CommandAction.PREPARE);
        var outcome = deviceCommands.dispatch(cabinet.id(), order.getSlotId(),
                com.wherelee.cabinet.device.api.CommandAction.PREPARE, order.getId(),
                order.getOrderNo() + ":PREPARE:" + (seq + 1));
        if (outcome.faultType() == com.wherelee.cabinet.device.api.FaultType.OPEN_FAILED) {
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
     * “换到一个有别人东西的格子”这种最坏结果。先抢新格再摘旧格：{@code reserveSlot} 自带条件，
     * 两人同时换也不会换到同一个；抢不到就试下一个而不是直接报失败。
     */
    private boolean swapToFreeSlot(BizStorageOrder order) {
        SlotSnapshot swapped = slotSwap.swapIn(order.getCabinetId(), order.getSizeType(),
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
                "格口打不开，已改用格口 " + swapped.slotNo(), LocalDateTime.now().plusHours(2));
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

    /**
     * 用户否认“柜内有我的东西”→ 触发 AI 看图复审（L3）。
     *
     * <p>判为误报则当场结束并把争议期间的费用免除；AI 仍判有物或无法判断时，订单不动、
     * <b>计费也不停</b>（I11）——否则“我否认一下”就是暂停计费的白嫖通道。
     *
     * <p><b>本方法故意不开事务</b>，两个理由缺一不可：
     * ① 复审是一次外部调用（模型），等它的时候不能握着数据库连接（第 11 刀拆事务的同一理由）；
     * ② 更隐蔽：这里先由 {@code ItemDisputeService.deny} 在独立事务里写了复审次数，
     * 如果本方法自开一个事务，它的快照是在那次提交<b>之前</b>定的，下面 finish 重新加载订单时
     * 读到的是旧值，紧接着的 updateById 会把刚写的复审次数**反向覆盖回 0**（实测到）。
     * 不开事务后两个写入各自成事务，顺序自然成立。教训：<b>REQUIRES_NEW 写完的东西，
     * 外层的全行 updateById 会把它抹掉</b>，不要靠“记得只更部分列”来防。
     */
    // denyItem（用户否认→AI 复审→换取结束许可）已删除（2026-10-12 用户令）：
    // 它的唯一产出是“允许结束”，而 19A 之后结束不需要任何人允许。
    // 上面那段关于“REQUIRES_NEW 写完的东西会被外层全行 updateById 抹掉”的教训仍然有效（已记入 docs/架构约定.md）。

    /**
     * 下一位使用者上报“这个格口里有别人的东西”。
     *
     * <p>这是唯一能兑住“物检漏检 + 当事人没及时发现”的路径：传感器看不到的东西，
     * 开门的人看得到。两个动作缺一不可：锁格（不得再卖）、上报者的单不收费退回；
     * 给原主打标与发站内消息由 markContentLeft 统一负责（第 21 刀，取回闭环的起点）。
     *
     * <p><b>上报者免费是故意的</b>：他的单可能已计费几分钟，一律按取消处理不收费。
     * 要他为一个不是自己造成的现场付钱，得到的只会是“下次不开这扇门直接走人”——
     * 而我们需要的正是他报这一句。
     */
    @Transactional
    public StorageOrderView reportLeftover(Long customerId, String orderNo) {
        BizStorageOrder order = findOwned(customerId, orderNo);
        if (order.getStatus() != OrderStatus.OPENING && order.getStatus() != OrderStatus.ACTIVE
                && order.getStatus() != OrderStatus.TEMP_OPEN) {
            throw new BizException(ResultCode.BIZ_ERROR, "只有正在使用的格口可以上报遗留物");
        }
        Long slotId = order.getSlotId();
        // 先标异常再释放：顺序反过来会有一个窗口让这一格“看起来是空的”被别人抢走
        states.markContentLeft(slotId, "下一位使用者开门发现他人遗留物，需业务运维清柜");
        log.warn("格口遗留物上报 orderNo={} slotId={} 上报人={}", order.getOrderNo(), slotId, customerId);
        return releaseOrder(order);
    }

    /**
     * 容错期满的收敛（由“门开未关”巡检调用）：把“不关门”从一个人工事件变成一个<b>计费事件</b>。
     *
     * <p>门还开着时做三件事：落定计费起点、把单推进到 ACTIVE（他确实在占用这个格子）、返回 true
     * 让 worker 续排下一轮提醒。<b>不转 ABNORMAL、不自动撤销</b>（S-16）：
     * 全城多点位派一次人工的成本远高于一个格口被占的损耗，而钱会把人叫回来。
     *
     * <p>门已经关了（他关上了却没点确认）：走与关门校验同一段收敛，不另写一遍——
     * 写两遍就会出现“巡检认为已存、实时路径认为没存”这种同事件不同结果。
     *
     * @param sensing 调用方（巡检 handler）在**事务外**探好的传感器读数。<b>故意不在这里探</b>：
     *                接真实设备后探测就是一次 RPC，把它放进 @Transactional 里等于拿一条
     *                数据库连接等柜机回答（第 9 刀量过的瓶颈，第 11 刀已经为这件事拆过一次事务）
     * @return true 表示还要继续盯着（下一轮提醒）；false 表示这条看管到此为止
     */
    @Transactional
    public boolean doorWatchTick(String orderNo, Sensing sensing) {
        BizStorageOrder order = orderMapper.selectOne(Wrappers.<BizStorageOrder>lambdaQuery()
                .eq(BizStorageOrder::getOrderNo, orderNo));
        if (order == null || order.getStatus().isTerminal()) {
            return false;
        }
        if (order.getToleranceUntil() == null) {
            // 这单从未开过门（例如测试直接埋了一条任务、或开柜失败后退回 RESERVED）：
            // 没有容错期可到期，也不该拿“门未关”去推它——推了就会造出“没开门却记了已存”的假现场
            log.debug("容错收敛跳过：该单没有开过门 orderNo={}", orderNo);
            return false;
        }
        if (sensing.doorClosed()) {
            // 门关了但没人点确认：按关门收敛推进（空不空的判定留给结束那一刻）
            deviceOutcomes.applyCloseVerified(order, sensing);
        } else {
            if (order.getStatus() == OrderStatus.OPENING) {
                order.transitTo(OrderStatus.ACTIVE);
            }
            LocalDateTime start = order.getToleranceUntil() == null ? LocalDateTime.now() : order.getToleranceUntil();
            deviceOutcomes.startBilling(order, start);
            // 到这里才真的是异常：门开着已超出容错、计费已经起计，必须进台账而不是只进日志
            states.markDoorOpenPastTolerance(order.getSlotId(),
                    java.time.Duration.between(start, LocalDateTime.now()).toMinutes());
        }
        if (orderMapper.updateById(order) == 0) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "订单已被并发修改，容错收敛下一轮再来 orderNo=" + orderNo);
        }
        return true;
    }

    /**
     * 超时回扫后的订单收敛：与实时路径共用同一张“按动作判”的映射表。
     *
     * <p>为什么开这个方法而不是让调度 handler 自己改状态：另写一遍映射就会有两个真相，
     * 迟早出现“实时把 OPEN 失败退回 RESERVED、回扫把它转成 ABNORMAL”这种同事件不同结果。
     *
     * <p>单已终态就不动（历史不回改）；并发修改让 updateById 影响 0 行时直接抛，
     * 让任务退避重跑而不是默不作声。
     */
    @Transactional
    public void convergeDeviceOutcome(Long orderId, com.wherelee.cabinet.device.api.CommandAction action,
                                      com.wherelee.cabinet.device.api.CommandOutcome outcome) {
        BizStorageOrder order = orderMapper.selectById(orderId);
        if (order == null || order.getStatus().isTerminal()) {
            log.info("回扫收敛跳过：单不存在或已终态 orderId={}", orderId);
            return;
        }
        deviceOutcomes.apply(order, action, outcome);
        if (orderMapper.updateById(order) == 0) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "订单已被并发修改，回扫下一轮再来 orderId=" + orderId);
        }
    }

    // waiveFalseAlarm（后台人工免单）与 denyItem（AI 复审换取结束许可）已删除
    // （2026-10-12 用户令：误报免除作废）。它们的靶子是“被拦住的争议期间产生的费用”——
    // 19A 结束不拦人之后这种费用不存在了，结束从来只算到此刻，也就没什么可“免”。

    /** 这条读数能不能当“柜内已空”的凭据用（坏了、离线、结论过期都算不能用）。 */
    private boolean emptyEvidence(Sensing sensing) {
        return sensing.presence() == com.wherelee.cabinet.locker.api.Presence.ABSENT && states.fresh(sensing);
    }

    private long countAttempts(String orderNo, com.wherelee.cabinet.device.api.CommandAction action) {
        // “第几次尝试”是设备侧的账：由 CommandPort 按 requestId 前缀计数，订单侧不再查指令表。
        return deviceCommands.countAttempts(orderNo + ":" + action.name() + ":");
    }

    private BizStorageOrder findOwned(Long customerId, String orderNo) {
        BizStorageOrder order = orderMapper.selectOne(Wrappers.<BizStorageOrder>lambdaQuery()
                .eq(BizStorageOrder::getOrderNo, orderNo));
        if (order == null || !order.getCustomerId().equals(customerId)) {
            // 不区分“不存在”与“不是你的”：避免用 404/403 差值枚举他人单号
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "寄存单不存在");
        }
        return order;
    }

    /** 记一次凭据错误（双因子取件路径也走同一套计数）。 */
    int registerVoucherWrong(BizStorageOrder order) {
        return vouchers.registerWrong(order);
    }

}
