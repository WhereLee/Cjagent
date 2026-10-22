package com.wherelee.cabinet.storage.app;

import com.wherelee.cabinet.notify.api.NoticeChannel;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.device.api.CommandAction;
import com.wherelee.cabinet.device.api.CommandOutcome;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.OrderStatus;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import com.wherelee.cabinet.locker.api.CabinetSnapshot;
import com.wherelee.cabinet.locker.api.CabinetStatus;
import com.wherelee.cabinet.locker.api.Sensing;
import com.wherelee.cabinet.locker.api.SizeType;
import com.wherelee.cabinet.locker.api.SlotAllocator;
import com.wherelee.cabinet.locker.api.SlotPort;
import com.wherelee.cabinet.locker.api.SlotQueryPort;
import com.wherelee.cabinet.notify.api.NoticePort;
import com.wherelee.cabinet.storage.api.CreateOrderCommand;
import com.wherelee.cabinet.storage.api.FundPort;
import com.wherelee.cabinet.storage.api.StorageOrderView;
import com.wherelee.cabinet.storage.api.VoucherView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 寄存单用例的门面：下单（create）、取消（cancel）、取件（pickup）、开柜（openDoor）、
 * 凭据自救（resetVoucher）、遗留物上报与两类后台收敛。
 *
 * <p><b>为什么本类越来越“薄”</b>：订单服务是最早的上帝类，四刀拆下来的顺序是
 * 凭据（{@link OrderVoucherService}）、设备结果（{@link OrderDeviceOutcomeService}）、
 * 取件结算（{@link OrderPickupFlowService}）、开柜会话（{@link OrderDoorSessionService}）、
 * 释放（{@link OrderReleaseService}）。留下的是<b>场景分派与对外签名</b>：
 * Controller/调度 handler 只认这个门面，内部协作者可以继续换而不牵动调用方。
 *
 * <p><b>为什么先预生成订单 ID 再占格口</b>：占用要把 {@code current_order_id} 写到格口上，
 * 而订单还没插就没有 ID。用雪花 ID 预生成（{@link IdWorker#getId()}）可以消除
 * "先插订单→再占格口→失败要删订单"这种补偿链。<b>顺序是：占格 → 插单</b>，
 * 中间任何一步抛异常都由事务整体回滚，不会留下"占了位没有单"的孤儿。
 *
 * <p>事务边界只在这一层：分配器不开事务（它必须能被塞进更大的用例里组合），Controller 也不开。
 */
@Service
public class StorageOrderService {

    private static final Logger log = LoggerFactory.getLogger(StorageOrderService.class);

    /** 格口/柜机/点位的只读查询：订单不直接读 locker 的表。 */
    private final SlotQueryPort slotQueries;
    private final BizStorageOrderMapper orderMapper;
    private final SlotAllocator allocator;
    /** 取件凭据：发码、验码、错次计数与锁定、重置。 */
    private final OrderVoucherService vouchers;
    /** 预收（账户押金 + 冻结）与解冻：下单事务内先算钱再插单。 */
    private final FundPort funds;
    /** 设备结果 → 订单状态、关门收敛与计费起点。 */
    private final OrderDeviceOutcomeService deviceOutcomes;
    /** 门态/物检/异常的唯一写入口（第 13B 刀）。 */
    private final SlotPort states;
    /** 欠费合计的只读查询（与后台展示同一口径，避免“后台说没欠、下单却被拒”）。 */
    private final BizStorageOrderMapper arrearsMapper;
    /** 取件凭据的站内消息（第 25 刀）：下单发一条，重置改写同一条。 */
    private final NoticePort notices;
    /**
     * 调度器延迟拿取：直接注入会形成循环依赖
     * （StorageOrderService → DelayTaskService → SlotReleaseHandler → StorageOrderService）。
     * 用 ObjectProvider 把解析推迟到第一次使用时，而不是用 @Lazy 把设计问题遮起来：
     * 这个环本身是有意的（超时释放就是取消），但构造期不能互相依赖。
     */
    private final ObjectProvider<com.wherelee.cabinet.platform.scheduling.DelayTaskService> taskScheduler;

    private final OrderLookup lookup;
    private final OrderReleaseService release;
    private final OrderPickupFlowService pickupFlow;
    private final OrderDoorSessionService doorSession;

    /**
     * 待收敛回收的阈值（定-4，用户 2026-10-10 定的 10 分钟）。
     *
     * <p>它不再是“给用户留的开柜宽限”（那个概念随预占一起删了）：下单与开门已合并成一步，
     * 正常路径不会停在“待开门”。这条任务只兜“崩在占格与开门之间”的残留，所定得宽一些无所谓，
     * 定短了反而会误伤柜机前翻包的人。
     */
    @Value("${cabinet.scheduler.reconcile-minutes:10}")
    private long reconcileMinutes;

    public StorageOrderService(SlotQueryPort slotQueries,
                               BizStorageOrderMapper orderMapper,
                               SlotAllocator allocator,
                               OrderVoucherService vouchers,
                               FundPort funds,
                               OrderDeviceOutcomeService deviceOutcomes,
                               SlotPort states,
                               BizStorageOrderMapper arrearsMapper,
                               ObjectProvider<com.wherelee.cabinet.platform.scheduling.DelayTaskService> taskScheduler,
                               NoticePort notices,
                               OrderLookup lookup,
                               OrderReleaseService release,
                               OrderPickupFlowService pickupFlow,
                               OrderDoorSessionService doorSession) {
        this.slotQueries = slotQueries;
        this.orderMapper = orderMapper;
        this.allocator = allocator;
        this.vouchers = vouchers;
        this.funds = funds;
        this.deviceOutcomes = deviceOutcomes;
        this.states = states;
        this.arrearsMapper = arrearsMapper;
        this.taskScheduler = taskScheduler;
        this.notices = notices;
        this.lookup = lookup;
        this.release = release;
        this.pickupFlow = pickupFlow;
        this.doorSession = doorSession;
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
        String createdSlotNo = lookup.slotNoOf(allocated.slotId());
        notices.emit(cabinet.tenantId(),
                NoticeChannel.SYSTEM_MESSAGE,
                "PICKUP_VOUCHER", order.getOrderNo() + ":voucher",
                vouchers.noteForExisting(order.getVoucherCode(), createdSlotNo),
                order.getCustomerId(), null, order.getOrderNo());
        return new StorageOrderView(order.getOrderNo(), cabinet.cabinetNo(), createdSlotNo,
                allocated.actualSize().name(), order.getStatus().name(), order.getEstimateMinutes(),
                allocated.triedSlots(), allocator.strategy(),
                vouchers.noteForNewOrder(order.getVoucherCode(), createdSlotNo));
    }

    /** 取消：柜内检查 + 释放顺序都在 {@link OrderReleaseService}，这里只保留对外签名。 */
    @Transactional
    public StorageOrderView cancel(Long customerId, String orderNo) {
        return release.cancel(customerId, orderNo);
    }

    /** 取件：算钱与开门的事务边界在 {@link OrderPickupFlowService}。 */
    public StorageOrderView pickup(Long customerId, String orderNo) {
        return pickupFlow.pickup(customerId, orderNo);
    }

    public StorageOrderView pickup(Long customerId, String orderNo, String voucherCode) {
        return pickupFlow.pickup(customerId, orderNo, voucherCode);
    }

    /** 开柜：三种动作与预备开握手在 {@link OrderDoorSessionService}。 */
    public StorageOrderView openDoor(Long customerId, String orderNo, CommandAction action) {
        return doorSession.openDoor(customerId, orderNo, action);
    }

    private SizeType parseSize(String value) {
        try {
            return SizeType.of(value);
        } catch (IllegalArgumentException e) {
            throw new BizException(ResultCode.PARAM_INVALID, "尺寸不合法：" + value);
        }
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
        BizStorageOrder order = lookup.findOwned(customerId, orderNo);
        if (order.getStatus().isTerminal()) {
            throw new BizException(ResultCode.BIZ_ERROR, "该单已结束，取件码已失效，无需重置");
        }
        String finalCode = vouchers.rotate(order);
        // 一条凭证消息始终反映当前码：改写而不是再发一条（消息页堆废码比没码更糟）
        notices.rewrite(orderNo + ":voucher",
                vouchers.noteForExisting(finalCode, lookup.slotNoOf(order.getSlotId())));
        return new VoucherView(orderNo, lookup.cabinetNoOf(order.getCabinetId()), lookup.slotNoOf(order.getSlotId()),
                order.getSizeType().name(), order.getStatus().name(), finalCode, false);
    }

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
        BizStorageOrder order = lookup.findOwned(customerId, orderNo);
        if (order.getStatus() != OrderStatus.OPENING && order.getStatus() != OrderStatus.ACTIVE
                && order.getStatus() != OrderStatus.TEMP_OPEN) {
            throw new BizException(ResultCode.BIZ_ERROR, "只有正在使用的格口可以上报遗留物");
        }
        Long slotId = order.getSlotId();
        // 先标异常再释放：顺序反过来会有一个窗口让这一格“看起来是空的”被别人抢走
        states.markContentLeft(slotId, "下一位使用者开门发现他人遗留物，需业务运维清柜");
        log.warn("格口遗留物上报 orderNo={} slotId={} 上报人={}", order.getOrderNo(), slotId, customerId);
        return release.releaseOrder(order);
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
            // 容错期必非空：上面已经拿它为 null 的情况提前 return 了，这里再判一次就是不可达分支
            LocalDateTime start = order.getToleranceUntil();
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
    public void convergeDeviceOutcome(Long orderId, CommandAction action, CommandOutcome outcome) {
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

    /** 记一次凭据错误（双因子取件路径也走同一套计数）。 */
    int registerVoucherWrong(BizStorageOrder order) {
        return vouchers.registerWrong(order);
    }
}
