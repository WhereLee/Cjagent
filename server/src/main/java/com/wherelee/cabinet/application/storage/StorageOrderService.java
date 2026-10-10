package com.wherelee.cabinet.application.storage;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.application.storage.dto.CreateOrderCommand;
import com.wherelee.cabinet.application.storage.dto.StorageOrderView;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.entity.BizCabinet;
import com.wherelee.cabinet.domain.entity.BizCompartment;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.CabinetStatus;
import com.wherelee.cabinet.domain.enums.OrderCloseReason;
import com.wherelee.cabinet.domain.enums.OrderStatus;
import com.wherelee.cabinet.domain.enums.SizeType;
import com.wherelee.cabinet.infrastructure.alloc.SlotPreDeductionService;
import com.wherelee.cabinet.infrastructure.mapper.BizCabinetMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizCompartmentMapper;
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

    private final BizCabinetMapper cabinetMapper;
    private final BizCompartmentMapper slotMapper;
    private final BizStorageOrderMapper orderMapper;
    private final SlotAllocator allocator;
    private final SlotPreDeductionService preDeduction;
    private final com.wherelee.cabinet.application.device.DeviceCommandService deviceCommands;
    private final com.wherelee.cabinet.infrastructure.mapper.BizDeviceCommandMapper deviceCommandMapper;
    private final com.wherelee.cabinet.application.point.OrderFundService funds;
    /** 门态/物检/异常的唯一写入口（第 13B 刀）。 */
    private final CompartmentStateService states;
    /** 柜内物品争议阶梯（第 13C 刀）：只写证据与争议字段，不管钱与主态。 */
    private final ItemDisputeService dispute;
    /** 欠费合计的只读查询（与后台展示同一口径，避免“后台说没欠、下单却被拒”）。 */
    private final com.wherelee.cabinet.infrastructure.mapper.BizLockerConsoleMapper consoleMapper;
    /** 容错期也可以按点位发布（第 14D 刀）；没发布时仍用配置默认值。 */
    private final com.wherelee.cabinet.application.billing.PriceRuleService priceRules;
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
     * 取件码连续输错的锁定阈值——<b>5 次由用户 2026-10-10 给出</b>，不进配置。
     *
     * <p>不做成可配参数是因为没人会去调它，但一个能配的东西就会被误配成 0（一输错就锁）
     * 或 99（锁定等于不存在）。真要改就改这一行，改动会进代码评审。
     */
    private static final int VOUCHER_MAX_WRONG = 5;

    /**
     * 开门后的容错期（分钟，第 13B 刀）：这段时间不计费，给用户挑包、放件、翻找。
     *
     * <p>取代原来的 {@code door-grace-minutes}：“多久该提醒”与“多久该起计”本质上是
     * 同一个时刻的两个说法，存两个近似参数总有一天会被配成不一样，那时没人说得清
     * 哪个是计费起点。到期同时做三件事：落定计费起点、标格口异常、发提醒。
     */
    @org.springframework.beans.factory.annotation.Value("${cabinet.pricing.tolerance-minutes:5}")
    private long toleranceMinutes;

    public StorageOrderService(BizCabinetMapper cabinetMapper,
                               BizCompartmentMapper slotMapper,
                               BizStorageOrderMapper orderMapper,
                               SlotAllocator allocator,
                               SlotPreDeductionService preDeduction,
                               com.wherelee.cabinet.application.device.DeviceCommandService deviceCommands,
                               com.wherelee.cabinet.infrastructure.mapper.BizDeviceCommandMapper deviceCommandMapper,
                               com.wherelee.cabinet.application.point.OrderFundService funds,
                               CompartmentStateService states,
                               ItemDisputeService dispute,
                               com.wherelee.cabinet.infrastructure.mapper.BizLockerConsoleMapper consoleMapper,
                               com.wherelee.cabinet.application.billing.PriceRuleService priceRules,
                               org.springframework.transaction.support.TransactionTemplate txTemplate,
                               org.springframework.beans.factory.ObjectProvider<com.wherelee.cabinet.application.task.DelayTaskService> taskScheduler) {
        this.cabinetMapper = cabinetMapper;
        this.slotMapper = slotMapper;
        this.orderMapper = orderMapper;
        this.allocator = allocator;
        this.preDeduction = preDeduction;
        this.deviceCommands = deviceCommands;
        this.deviceCommandMapper = deviceCommandMapper;
        this.funds = funds;
        this.states = states;
        this.dispute = dispute;
        this.consoleMapper = consoleMapper;
        this.priceRules = priceRules;
        this.newTx = new org.springframework.transaction.support.TransactionTemplate(
                txTemplate.getTransactionManager());
        this.newTx.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.taskScheduler = taskScheduler;
    }

    @Transactional
    public StorageOrderView create(Long customerId, CreateOrderCommand command) {
        BizCabinet cabinet = cabinetMapper.selectOne(Wrappers.<BizCabinet>lambdaQuery()
                .eq(BizCabinet::getCabinetNo, command.cabinetNo()));
        if (cabinet == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "柜机不存在：" + command.cabinetNo());
        }
        // 停用/故障的柜机不接受新寄存；但"离线"不在这里挡——离线仍可取件（降级方向见 S-09）
        if (cabinet.getCabinetStatus() != CabinetStatus.ENABLED) {
            throw new BizException(ResultCode.BIZ_ERROR, "该柜机暂停服务，请选择邻近柜机");
        }

        SizeType required = parseSize(command.sizeType());
        // 欠费拦截：**欠费 > 0 就不得再下单，没有阈值**（第 12 刀只做了“只记不锁件”，
        // 拦下一环始终没落，所以始终“欠着钱还能一直下单”）。它不靠前端置灰：
        // 那只是个提示，绕过一个 POST 就能白用。
        // 取件仍绝不拦（欠的是债不是留置权），只拦住“新用户服务”这个入口。
        long owing = consoleMapper.sumCustomerArrears(customerId);
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
        SlotAllocator.AllocatedSlot allocated = allocator.allocate(cabinet.getId(), required, orderId);

        BizStorageOrder order = new BizStorageOrder();
        order.setId(orderId);
        order.setOrderNo("SO" + orderId);
        order.setSiteId(cabinet.getSiteId());
        order.setCabinetId(cabinet.getId());
        order.setSlotId(allocated.slotId());
        order.setSizeType(allocated.actualSize());
        order.setCustomerId(customerId);
        order.setVoucherCode(voucherOf(orderId));
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
            orderMapper.insert(order);
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

        return new StorageOrderView(order.getOrderNo(), cabinet.getCabinetNo(), slotNoOf(allocated.slotId()),
                allocated.actualSize().name(), order.getStatus().name(), order.getEstimateMinutes(),
                allocated.triedSlots(), allocator.strategy());
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
        CompartmentStateService.Sensing sensing = states.sense(order.getCabinetId(), order.getSlotId());
        if (sensing.presence() == com.wherelee.cabinet.domain.enums.Presence.PRESENT) {
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
        int released = slotMapper.releaseSlot(order.getSlotId(), order.getId());
        if (released == 0) {
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
                preDeduction.addFree(order.getCabinetId(), order.getSizeType(), order.getSlotId());
            } catch (RuntimeException e) {
                log.warn("格口已回 FREE 但 Redis 空闲集合同步失败（等校准修复）slotId={}", order.getSlotId(), e);
            }
        }
        return new StorageOrderView(order.getOrderNo(), cabinetNoOf(order.getCabinetId()),
                slotNoOf(order.getSlotId()), order.getSizeType().name(), order.getStatus().name(),
                order.getEstimateMinutes(), 0, allocator.strategy());
    }

    private SizeType parseSize(String value) {
        try {
            return SizeType.of(value);
        } catch (IllegalArgumentException e) {
            throw new BizException(ResultCode.PARAM_INVALID, "尺寸不合法：" + value);
        }
    }

    /**
     * 取件码：随机不可枚举。
     *
     * <p>不用订单号后 6 位——订单号是雪花 ID，相邻注册即可猜测，等于把别人的行李交给任何人。
     */
    private String voucherOf(Long orderId) {
        int random = java.util.concurrent.ThreadLocalRandom.current().nextInt(100000, 1000000);
        return String.format("V%06d", random);
    }

    private String slotNoOf(Long slotId) {
        BizCompartment slot = slotMapper.selectById(slotId);
        return slot == null ? "-" : slot.getSlotNo();
    }

    private String cabinetNoOf(Long cabinetId) {
        BizCabinet cabinet = cabinetMapper.selectById(cabinetId);
        return cabinet == null ? "-" : cabinet.getCabinetNo();
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
     * 取件（用户当面结束）：<b>门关 + 柜内无物才停计费</b>。
     *
     * <p>计费时长从 {@code started_at} 算到此刻，全部用服务端时间（S-07）。
     * 没落定过起点（容错期内就结束）就是 0 分钟——他确实还没占用过这个格子。
     */
    @Transactional
    public StorageOrderView pickup(Long customerId, String orderNo) {
        return finish(customerId, orderNo, true, OrderCloseReason.NORMAL);
    }

    /**
     * 带取件码的取件入口（定-6）。
     *
     * <p>码不是必填：**主路径是登录态**（用户在自己手机上点结束）；
     * 柜机上输码是手机没电时的第二条路径，柜机批次会带这个参数过来。
     * 不填码时行为与以前一致，不会因此拒任何合法用户。
     */
    @Transactional
    public StorageOrderView pickup(Long customerId, String orderNo, String voucherCode) {
        BizStorageOrder order = findOwned(customerId, orderNo);
        verifyVoucher(order, voucherCode);
        return finish(customerId, orderNo, true, OrderCloseReason.NORMAL);
    }

    /**
     * 校验取件码并累计错次。
     *
     * <p>两个判据分开写：<b>锁定</b>是“错太多，暂停这条凭据”，<b>错</b>是“本次不对”——
     * 混在一起会变成“一输错就锁”或“锁了还继续给次数”。阈值 5 是用户 2026-10-10 给的数。
     * 正确码会把计数归零（所以上限是“连续”错 5 次，不是“一共”5 次）。
     */
    private void verifyVoucher(BizStorageOrder order, String voucherCode) {
        requireNotVoucherLocked(order);
        if (voucherCode == null || voucherCode.isBlank()) {
            return;
        }
        if (voucherCode.trim().equals(order.getVoucherCode())) {
            // 成功路径不抛异常，所以留在外层事务里改（并把内存对象也改对，
            // 不然随后的 finish 会用陈旧的计数把零盖回去）
            if (order.getVoucherWrongCount() != null && order.getVoucherWrongCount() > 0) {
                order.setVoucherWrongCount(0);
                orderMapper.updateById(order);
            }
            return;
        }
        int wrong = (order.getVoucherWrongCount() == null ? 0 : order.getVoucherWrongCount()) + 1;
        boolean lock = wrong >= VOUCHER_MAX_WRONG;
        LocalDateTime at = lock ? LocalDateTime.now() : null;
        final int counted = wrong;
        // 先落库再抛错：这两列要活过外层回滚，不然锁定永远不生效
        newTx.executeWithoutResult(status -> {
            BizStorageOrder fresh = orderMapper.selectById(order.getId());
            if (fresh == null) {
                return;
            }
            fresh.setVoucherWrongCount(counted);
            if (at != null) {
                fresh.setVoucherLockedAt(at);
            }
            orderMapper.updateById(fresh);
        });
        order.setVoucherWrongCount(counted);
        if (lock) {
            order.setVoucherLockedAt(at);
            log.warn("取件码连续输错 {} 次，锁定该单开柜能力 orderNo={} customer={}",
                    counted, order.getOrderNo(), order.getCustomerId());
            throw new BizException(ResultCode.BIZ_ERROR,
                    "取件码连续输错次数过多，本单开柜已锁定；请联系客服核验后处理");
        }
        throw new BizException(ResultCode.BIZ_ERROR,
                "取件码不正确（还剩 " + (VOUCHER_MAX_WRONG - counted) + " 次机会）");
    }

    private void requireNotVoucherLocked(BizStorageOrder order) {
        if (order.getVoucherLockedAt() != null) {
            throw new BizException(ResultCode.BIZ_ERROR,
                    "该单因取件码连续输错已锁定开柜，请联系客服核验（后台可强制开柜并留审计）");
        }
    }

    /**
     * 声明“里面的东西不要了”并结束：这是柜内有物时唯一的自助出口。
     *
     * <p><b>只豁免“柜内无物”这一条，不豁免“门关”</b>：门开着就不是结束，而是还在占用。
     * 结束后格口转 {@code CONTENT_LEFT}：单结束了、钱到此为止，但这一格在有人清走东西之前
     * 不能再卖（不变量 I12：“钱走了、东西还在柜里、格子又卖给了第三个人”是整份设计里最坏的结果）。
     */
    @Transactional
    public StorageOrderView abandon(Long customerId, String orderNo) {
        return finish(customerId, orderNo, true, OrderCloseReason.ABANDONED);
    }

    /**
     * 远程结束订单（人不在现场）：同一套判据，不豁免任何一条。
     *
     * <p>如果远程能绕过“无物”，那“离开现场”就比“留在现场”更容易脱身，方向正好反了。
     * 费用 = 计时计费 + 该格口 {@code remote-close-hours} 小时单价的加收。
     */
    @Transactional
    public StorageOrderView remoteClose(Long customerId, String orderNo) {
        return finish(customerId, orderNo, false, OrderCloseReason.REMOTE);
    }

    /**
     * 结束订单的共用收口：三个入口（当面结束 / 声明放弃 / 远程结束）只差在“谁在作证”与“收不收加收”。
     *
     * <p>写成一个而不是三份：分开写过几天就会出现“当面结束会退押金、远程结束漏了退”这种
     * 只在一半路径上修的 bug（第 12 刀已经为退款路径合并过一次）。
     */
    private StorageOrderView finish(Long customerId, String orderNo, boolean atSite, OrderCloseReason reason) {
        return finish(customerId, orderNo, atSite, reason, false);
    }

    /**
     * @param waivedByFalseAlarm AI 复审已判为设备误报：跳过“柜内无物”这一条（它就是为这一步服务的），
     *                           并把结算终点回退到争议起始时刻——争议期间的计费本就不该由用户担。
     *                           <b>门没关仍不给结束</b>：这一条与凭据无关，不能随复审一起豁免。
     */
    private StorageOrderView finish(Long customerId, String orderNo, boolean atSite, OrderCloseReason reason,
                                    boolean waivedByFalseAlarm) {
        BizStorageOrder order = findOwned(customerId, orderNo);
        if (order.getStatus() != OrderStatus.ACTIVE && order.getStatus() != OrderStatus.TEMP_OPEN
                && order.getStatus() != OrderStatus.EXPIRED) {
            throw new BizException(ResultCode.BIZ_ERROR, "当前状态不可结束：" + order.getStatus());
        }
        CompartmentStateService.Sensing sensing = states.sense(order.getCabinetId(), order.getSlotId());
        CloseCriteria.Verdict verdict;
        if (reason == OrderCloseReason.ABANDONED) {
            // 放弃只豁免“无物”，门没关仍不能结束
            if (!sensing.doorClosed()) {
                throw new BizException(ResultCode.BIZ_ERROR,
                        "柜门还没关上：请先关好门再结束（声明放弃物品不能代替代关门）");
            }
            // 放弃本身就是“无凭据也结束”，所以调用方要锁格（leavesAnomaly）
            verdict = CloseCriteria.Verdict.closeWithoutEvidence(null);
        } else if (waivedByFalseAlarm) {
            // 复审已经推定“无物”，所以只守门这一条；不这么处理会陷入“因有物被拦 → 复审判误报 → 仍因有物不能结束”
            if (!sensing.doorClosed()) {
                throw new BizException(ResultCode.BIZ_ERROR, CloseCriteria.evaluate(
                        false, sensing.presence(), true, atSite).userMessage());
            }
            verdict = CloseCriteria.Verdict.closeWithEvidence();
        } else {
            verdict = CloseCriteria.evaluate(sensing.doorClosed(), sensing.presence(),
                    states.fresh(sensing), atSite);
            if (!verdict.closable()) {
                if (verdict.blocker() == CloseCriteria.Blocker.CONTENT_PRESENT) {
                    // 争议起始时刻要活过这次回滚（REQUIRES_NEW），否则事后既无法误报免除也无法统计误报率
                    dispute.markBlocked(order, com.wherelee.cabinet.domain.enums.Presence.PRESENT,
                            atSite ? "现场结束被拒：物检检测到柜内物品"
                                    : "远程结束被拒：物检检测到柜内物品");
                }
                throw new BizException(ResultCode.BIZ_ERROR, verdict.userMessage());
            }
        }

        // 误报免除：计费终点回到争议开始那一刻（争议期间是平台在等判定，不该用户付费）
        LocalDateTime settleEnd = waivedByFalseAlarm && order.getDisputeStartedAt() != null
                ? order.getDisputeStartedAt() : LocalDateTime.now();
        long actualMinutes = order.getStartedAt() == null ? 0L
                : Math.max(0L, Duration.between(order.getStartedAt(), settleEnd).toMinutes());
        funds.settle(order, actualMinutes, reason);

        if (reason.leavesAnomaly()) {
            states.markContentLeft(order.getSlotId(), "用户声明放弃柜内物品，需业务运维清柜后才能重新分配");
        } else if (!verdict.evidenceBacked()) {
            // 现场没有拿到“柜内已空”的凭据（传感器坏了）：人可以走，不能把他的脚钉在柜机前；
            // 但这个格子必须锁住等一次人来确认——否则就是“无凭据地把格子放回可售池”，
            // 而整条结束判据当初要防的就是这件事
            states.markContentUnverified(order.getSlotId(),
                    "现场结束订单时设备无法确认柜内已清空（物检不可用），需现场确认");
        } else {
            // 凭据齐全：能自动解除的异常就解除（只有 DOOR_OPEN 这一类可自动，见 I9）
            states.tryAutoRecover(order.getSlotId(), sensing);
        }

        BizCabinet cabinet = cabinetMapper.selectById(order.getCabinetId());
        return new StorageOrderView(order.getOrderNo(), cabinet == null ? "-" : cabinet.getCabinetNo(),
                slotNoOf(order.getSlotId()), order.getSizeType().name(), order.getStatus().name(),
                order.getEstimateMinutes(), 0, allocator.strategy());
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
                                     com.wherelee.cabinet.domain.enums.CommandAction action) {
        BizStorageOrder order = findOwned(customerId, orderNo);
        // 取件码连错锁定后，**开柜能力**被锁（不管首次投件还是中途取物）；
        // 但“关门校验”与“结束订单”不拦——结束不需要开门，把他困在柜子前不是本锁的目的。
        if (action != com.wherelee.cabinet.domain.enums.CommandAction.CLOSE_VERIFY) {
            requireNotVoucherLocked(order);
        }
        BizCabinet cabinet = cabinetMapper.selectById(order.getCabinetId());
        if (cabinet == null) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "柜机不存在，需人工核对 orderNo=" + orderNo);
        }

        boolean opening = action == com.wherelee.cabinet.domain.enums.CommandAction.OPEN;
        boolean tempOpen = action == com.wherelee.cabinet.domain.enums.CommandAction.OPEN_TEMP;
        boolean verifyClose = action == com.wherelee.cabinet.domain.enums.CommandAction.CLOSE_VERIFY;

        if (opening && order.getStatus() != OrderStatus.RESERVED) {
            throw new BizException(ResultCode.BIZ_ERROR, "当前状态不可开柜：" + order.getStatus());
        }
        if (tempOpen && order.getStatus() != OrderStatus.ACTIVE) {
            throw new BizException(ResultCode.BIZ_ERROR, "只有计费中的订单可以临时开柜");
        }
        if (verifyClose && !mayCloseVerify(order)) {
            throw new BizException(ResultCode.BIZ_ERROR,
                    "没有待关闭的柜门（当前状态 " + order.getStatus() + "）");
        }

        if (opening) {
            order.transitTo(OrderStatus.OPENING);
            orderMapper.updateById(order);
        }

        long attempts = countAttempts(orderNo, action);
        String requestId = orderNo + ":" + action.name() + ":" + (attempts + 1);
        com.wherelee.cabinet.application.device.DeviceCommandService.CommandOutcome outcome;
        try {
            outcome = deviceCommands.dispatch(cabinet, order.getSlotId(), action, order.getId(), requestId);
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

        applyDeviceOutcome(order, action, outcome);
        if (orderMapper.updateById(order) == 0) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "订单已被并发修改，请重试 orderNo=" + orderNo);
        }

        return new StorageOrderView(order.getOrderNo(), cabinet.getCabinetNo(), slotNoOf(order.getSlotId()),
                order.getSizeType().name(), order.getStatus().name(), order.getEstimateMinutes(),
                0, allocator.strategy());
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
    private void applyDeviceOutcome(BizStorageOrder order,
                                    com.wherelee.cabinet.domain.enums.CommandAction action,
                                    com.wherelee.cabinet.application.device.DeviceCommandService.CommandOutcome outcome) {
        if (outcome.stale()) {
            // 旧事件：什么都不改（设备重放不得把新状态覆盖成旧事件）
            return;
        }
        if (outcome.businessSuccess()) {
            switch (action) {
                case OPEN -> {
                    // 门真的开了才记下起点：从这一刻起容错计时开始跑。
                    // “门开了没人关”的看管也从这里登记——如果只在关门后登记，
                    // 用户开完门就走，单会停在 OPENING、格口停在 RESERVED、押金一直冻着，没人收场
                    states.afterDoorOpened(states.reload(order.getSlotId()), order.getCustomerId());
                    log.info("格口已开门 orderNo={} slotId={}", order.getOrderNo(), order.getSlotId());
                    scheduleDoorWatch(order);
                }
                case OPEN_TEMP -> {
                    order.setTempOpenCount(order.getTempOpenCount() == null ? 1 : order.getTempOpenCount() + 1);
                    order.transitTo(OrderStatus.TEMP_OPEN);
                    states.afterDoorOpened(states.reload(order.getSlotId()), order.getCustomerId());
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
     *
     * @param atSite true=用户当面点“我关好了”（他就在柜子前）；
     *               false=事后回扫替系统收敛（没有一个活人在现场作证）
     */
    private void closeVerified(BizStorageOrder order) {
        applyCloseVerified(order, states.sense(order.getCabinetId(), order.getSlotId()));
    }

    /**
     * 关门收敛。<b>它只回答“门关了没有”，不回答“柜内空了没有”</b>：
     * 后者只在结束订单那一刻判（结束三条件），拿它卡住投件/临时开柜的关门是错的——
     * 投件时柜内本来就该有东西。
     */
    private void applyCloseVerified(BizStorageOrder order, CompartmentStateService.Sensing sensing) {
        if (!sensing.doorClosed()) {
            // 指令层成功而门磁不认：不推进。宁可让用户再点一次，也不能把“没关上”记成已存
            states.markSensorConflict(order.getSlotId(), "收到关门校验但门磁仍报开着，需现场确认");
            log.warn("关门校验未被门磁确认 orderNo={} slotId={}", order.getOrderNo(), order.getSlotId());
            return;
        }
        states.afterDoorClosed(states.reload(order.getSlotId()), order.getCustomerId());
        if (order.getStatus() == OrderStatus.TEMP_OPEN) {
            // 中途取物后又关回来：本来就在计费，起点不动（I10）
            order.transitTo(OrderStatus.ACTIVE);
        } else if (order.getStatus() == OrderStatus.OPENING || order.getStatus() == OrderStatus.RESERVED) {
            // 容错期满已被巡检推进成 ACTIVE 时走不到这里；走到这里说明这是正常的投件关门
            if (slotMapper.markOccupied(order.getSlotId(), order.getId()) == 0) {
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
     * <p>起点取“关门时刻”与“容错到期时刻”中较早的一个：容错期内关门就从关门起计，
     * 超容错没关门则从到期时起计（所以“开门不关门”不是免费的）。
     * 巡检任务比这里晚到也不影响金额：两边都算到同一个 toleranceUntil。
     */
    private void startBilling(BizStorageOrder order, LocalDateTime closedAt) {
        if (order.getStartedAt() != null) {
            return;
        }
        LocalDateTime tolerance = order.getToleranceUntil();
        LocalDateTime start = tolerance == null || closedAt.isBefore(tolerance) ? closedAt : tolerance;
        order.setStartedAt(start);
        // C 定案：没有预估时长了，“多久算逾期”只能由已定的总额封顶天数推出来
        // （capDays=0 表示不限，那就不设 deadline，由封顶与看管窗口自己收口）
        int capDays = pricing.capDaysOf(order.getPricingSnapshot());
        order.setExpectedFinishAt(capDays > 0 ? start.plusDays(capDays) : null);
        var scheduler = taskScheduler.getIfAvailable();
        if (scheduler != null && order.getExpectedFinishAt() != null) {
            // 到期即逾期，不再额外加宽限：那个宽限是“hold-grace”时代的残留，没人定义过它该多大
            scheduler.schedule(com.wherelee.cabinet.domain.enums.TaskType.OVERDUE_PICKUP,
                    order.getOrderNo(), order.getTenantId(), order.getExpectedFinishAt());
        }
    }

    /**
     * 能不能做关门校验。<b>判据是“有没有一扇开着的门要关”，而不是“单在哪个状态”</b>。
     *
     * <p>为什么不能只看状态：容错期满时巡检会把单推进到 ACTIVE（开始计费），而用户后来
     * 还是会回来关门——如果只允许 OPENING/TEMP_OPEN 做校验，那“门开着超时了、人又回来关上”
     * 这条必然会发生的路就推不动了，而它恰恰是新规则下最常见的一种收场。
     */
    private boolean mayCloseVerify(BizStorageOrder order) {
        if (order.getStatus() == OrderStatus.OPENING || order.getStatus() == OrderStatus.TEMP_OPEN) {
            return true;
        }
        // 已在计费但门还开着：该让他关上（关上后才能结束）
        BizCompartment slot = slotMapper.selectById(order.getSlotId());
        return order.getStatus() == OrderStatus.ACTIVE && slot != null && slot.doorOpen();
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
    public StorageOrderView denyItem(Long customerId, String orderNo) {
        BizStorageOrder order = findOwned(customerId, orderNo);
        if (!order.inDispute()) {
            // 不先拦下就没有争议可复审：这个前置让“绕过阶梯直接拿 AI 结论免单”在结构上不存在
            throw new BizException(ResultCode.BIZ_ERROR, "当前没有待复核的柜内物品争议");
        }
        ItemDisputeService.Decision decision = dispute.deny(order);
        if (decision.outcome() != ItemDisputeService.Outcome.FALSE_ALARM) {
            throw new BizException(ResultCode.BIZ_ERROR, decision.message());
        }
        return finish(customerId, orderNo, true, OrderCloseReason.DISPUTE_WAIVED, true);
    }

    /**
     * 下一位使用者上报“这个格口里有别人的东西”。
     *
     * <p>这是唯一能兑住“物检漏检 + 当事人没及时发现”的路径：传感器看不到的东西，
     * 开门的人看得到。三个动作缺一不可：锁格（不得再卖）、上报者的单不收费退回、
     * 给原主单打标（他下次看页面时能知道，也是纠纷还原的依据）。
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
        markOriginalOwner(order);
        log.warn("格口遗留物上报 orderNo={} slotId={} 上报人={}", order.getOrderNo(), slotId, customerId);
        return releaseOrder(order);
    }

    /**
     * 给同一格口上一个终态单打标（“你的东西被别人看到过”）。
     *
     * <p>找不到也不影响上报成立：遗留物可能属于很久以前那张单，甚至属于一张被物理清柜时的无主物；
     * 这种情况不报错而是让台账自己说（否则一个上报会被“无法归因”直接拒绝，那是把好处处推给用户）。
     */
    private void markOriginalOwner(BizStorageOrder current) {
        BizStorageOrder previous = orderMapper.selectOne(Wrappers.<BizStorageOrder>lambdaQuery()
                .eq(BizStorageOrder::getSlotId, current.getSlotId())
                .ne(BizStorageOrder::getId, current.getId())
                .in(BizStorageOrder::getStatus, OrderStatus.CLOSED, OrderStatus.CANCELLED)
                .orderByDesc(BizStorageOrder::getFinishedAt)
                .last("limit 1"));
        if (previous == null) {
            log.info("遗留物无法归因到上一张单，仅记台账 slotId={}", current.getSlotId());
            return;
        }
        int marked = orderMapper.markLeftoverReported(previous.getId(), LocalDateTime.now());
        if (marked == 0) {
            log.debug("该单已被上报过一次，不刷时间 orderNo={}", previous.getOrderNo());
        }
    }

    /**
     * 这张单的容错期：<b>算价与计时必须是同一版策略</b>。
     *
     * <p>不然会出现“按站点 A 的价收费，却用全局的容错时长”，两边都能自圆其说但没人能对账。
     * 没发布过策略时退回配置默认值，与下单冻结时取价的回退路径一致。
     */
    private long toleranceOf(BizStorageOrder order) {
        com.wherelee.cabinet.domain.entity.BizPriceRule rule = priceRules.effective(order.getSiteId());
        return rule == null ? toleranceMinutes : rule.getToleranceMinutes();
    }

    /** 登记“门开未关”看管，并把容错到期时刻定下来（两者是同一个时刻）。 */
    private void scheduleDoorWatch(BizStorageOrder order) {
        LocalDateTime toleranceUntil = LocalDateTime.now().plusMinutes(toleranceOf(order));
        if (order.getToleranceUntil() == null) {
            // 临时开柜时早已有过容错期：不得把起点刷新，否则“又开一次门”会把计费起点往后推
            order.setToleranceUntil(toleranceUntil);
        }
        var scheduler = taskScheduler.getIfAvailable();
        if (scheduler == null) {
            return;
        }
        scheduler.schedule(com.wherelee.cabinet.domain.enums.TaskType.DOOR_NOT_CLOSED,
                order.getOrderNo(), order.getTenantId(), order.getToleranceUntil());
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
    public boolean toleranceExpired(String orderNo, CompartmentStateService.Sensing sensing) {
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
            applyCloseVerified(order, sensing);
        } else {
            if (order.getStatus() == OrderStatus.OPENING) {
                order.transitTo(OrderStatus.ACTIVE);
            }
            LocalDateTime start = order.getToleranceUntil() == null ? LocalDateTime.now() : order.getToleranceUntil();
            startBilling(order, start);
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
    public void convergeDeviceOutcome(Long orderId, com.wherelee.cabinet.domain.enums.CommandAction action,
                                      com.wherelee.cabinet.application.device.DeviceCommandService.CommandOutcome outcome) {
        BizStorageOrder order = orderMapper.selectById(orderId);
        if (order == null || order.getStatus().isTerminal()) {
            log.info("回扫收敛跳过：单不存在或已终态 orderId={}", orderId);
            return;
        }
        applyDeviceOutcome(order, action, outcome);
        if (orderMapper.updateById(order) == 0) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "订单已被并发修改，回扫下一轮再来 orderId=" + orderId);
        }
    }

    /**
     * 后台确认设备误报后的免除与结束（第 14 刀的人工入口）：与 AI 复审放行走同一段结算代码，
     * 金额依旧只算到争议起始时刻。
     *
     * <p>为什么不另写一套“人工免单”：两套算法迟早对不上账（同一张单，AI 放行免 15 点、
     * 人工免 30 点）。“谁来点”是权限问题，“免多少”不是业务能商量的事。
     */
    @Transactional
    public void waiveFalseAlarm(Long orderId) {
        BizStorageOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "寄存单不存在");
        }
        if (order.getStatus().isTerminal()) {
            throw new BizException(ResultCode.BIZ_ERROR, "该单已结束，不能再次免除");
        }
        CompartmentStateService.Sensing sensing = states.sense(order.getCabinetId(), order.getSlotId());
        if (!sensing.doorClosed()) {
            // 门这条与凭据无关：误报免除只豁免“柜内无物”，不豁免“门关”
            throw new BizException(ResultCode.BIZ_ERROR, "柜门尚未关闭，不能结束计费");
        }
        long actualMinutes = order.getStartedAt() == null ? 0L
                : Math.max(0L, Duration.between(order.getStartedAt(),
                order.getDisputeStartedAt() == null ? LocalDateTime.now() : order.getDisputeStartedAt()).toMinutes());
        funds.settle(order, actualMinutes, OrderCloseReason.DISPUTE_WAIVED);
        // 不额外标异常：免除的依据本身就是“AI 看图说没东西”这条凭据，
        // 再锁一次格子等于否认自己刚用的判据（与 AI 复审放行那条路径保持一致，两处不得两套做法）
        log.info("人工判定误报并免除争议期间费用 orderNo={} 计费分钟={}", order.getOrderNo(), actualMinutes);
    }

    /** 这条读数能不能当“柜内已空”的凭据用（坏了、离线、结论过期都算不能用）。 */
    private boolean emptyEvidence(CompartmentStateService.Sensing sensing) {
        return sensing.presence() == com.wherelee.cabinet.domain.enums.Presence.ABSENT && states.fresh(sensing);
    }

    private long countAttempts(String orderNo, com.wherelee.cabinet.domain.enums.CommandAction action) {
        return deviceCommandMapper.selectCount(Wrappers.<com.wherelee.cabinet.domain.entity.BizDeviceCommand>lambdaQuery()
                .likeRight(com.wherelee.cabinet.domain.entity.BizDeviceCommand::getRequestId,
                        orderNo + ":" + action.name() + ":"));
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
}
