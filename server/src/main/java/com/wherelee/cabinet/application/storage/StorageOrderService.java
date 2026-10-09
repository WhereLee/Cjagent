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

    public StorageOrderService(BizCabinetMapper cabinetMapper,
                               BizCompartmentMapper slotMapper,
                               BizStorageOrderMapper orderMapper,
                               SlotAllocator allocator,
                               SlotPreDeductionService preDeduction,
                               com.wherelee.cabinet.application.device.DeviceCommandService deviceCommands,
                               com.wherelee.cabinet.infrastructure.mapper.BizDeviceCommandMapper deviceCommandMapper,
                               com.wherelee.cabinet.application.point.OrderFundService funds) {
        this.cabinetMapper = cabinetMapper;
        this.slotMapper = slotMapper;
        this.orderMapper = orderMapper;
        this.allocator = allocator;
        this.preDeduction = preDeduction;
        this.deviceCommands = deviceCommands;
        this.deviceCommandMapper = deviceCommandMapper;
        this.funds = funds;
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
        order.setEstimateMinutes(command.estimateMinutes());
        order.setTempOpenCount(0);
        order.setDepositPoints(0L);
        order.setFrozenPoints(0L);
        order.setSettledPoints(0L);
        order.setArrearsPoints(0L);
        // 计费快照：第 12 刀接真实策略。改价不得影响历史单，所以这里存的是"当时怎么算的"
        order.setPricingSnapshot("{\"strategy\":\"placeholder\",\"estimateMinutes\":"
                + command.estimateMinutes() + ",\"sizeType\":\"" + allocated.actualSize() + "\"}");
        order.initStatus();
        order.transitTo(OrderStatus.RESERVED);

        try {
            // 先算钱再插单：holdFunds 只往订单对象上写快照与冻结额，一次 insert 就带着它们落库。
            // 之前先 insert 再补一次 updateById，因为 BizStorageOrder 带 @Version，
            // 那次 update 影响 0 行却被忽略，定价快照静默丢失（结算时才发现）——
            // 顺序改对比“记住检查每个 updateById”更可靠。
            funds.holdFunds(order, command.estimateMinutes());
            orderMapper.insert(order);
        } catch (RuntimeException e) {
            // 罕见但必须留痕：格口已绑单而订单没落库。事务会回滚掉占用，
            // 但如果不打日志，压测时看到的就是"莫名失败"而查不到根因
            log.error("订单落库失败，格口占用将随事务回滚 orderId={} slotId={} strategy={}",
                    orderId, allocated.slotId(), allocator.strategy(), e);
            throw e;
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

        order.transitTo(OrderStatus.CANCELLED);
        int released = slotMapper.releaseSlot(order.getSlotId(), order.getId());
        if (released == 0) {
            // 状态说该释放却释放不掉：说明格口已被他人改写，必须报错回滚而不是"取消成功但格口仍占着"
            throw new BizException(ResultCode.SYSTEM_ERROR, "格口状态与订单不一致，请联系运营处理");
        }
        // 取消同样要把押金与预估全额解冻；与取件共用同一段代码，避免两处各自维护把押金退漏
        funds.cancelHold(order);
        if (orderMapper.updateById(order) == 0) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "订单已被并发修改，请重试 orderNo=" + orderNo);
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
     * 取件：结算 + 退押金 + 释放格口。
     *
     * <p>计费时长由**服务端时间**算（started_at → 此刻），不接受客户端上报的时长：
     * 设备与手机时钟都不可信，而这里是真金白银（S-07）。
     */
    @Transactional
    public StorageOrderView pickup(Long customerId, String orderNo) {
        BizStorageOrder order = findOwned(customerId, orderNo);
        if (order.getStatus() != OrderStatus.ACTIVE && order.getStatus() != OrderStatus.TEMP_OPEN
                && order.getStatus() != OrderStatus.EXPIRED) {
            throw new BizException(ResultCode.BIZ_ERROR, "当前状态不可取件：" + order.getStatus());
        }
        LocalDateTime from = order.getStartedAt() != null ? order.getStartedAt() : order.getCreateTime();
        long actualMinutes = Math.max(0L, Duration.between(from, LocalDateTime.now()).toMinutes());

        funds.settle(order, actualMinutes);

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
        if (verifyClose && order.getStatus() != OrderStatus.OPENING
                && order.getStatus() != OrderStatus.TEMP_OPEN) {
            throw new BizException(ResultCode.BIZ_ERROR, "没有待关闭的柜门");
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
     *   <li>{@code CLOSE_VERIFY} 失败/谎报 → <b>件已在柜内</b> → 只能 {@code ABNORMAL} 等人工；</li>
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
                    // 门已开，等用户投件 + 关门校验；此时仍是 OPENING，不能提前记为已存
                    log.info("格口已开门 orderNo={} slotId={}", order.getOrderNo(), order.getSlotId());
                }
                case OPEN_TEMP -> {
                    order.setTempOpenCount(order.getTempOpenCount() == null ? 1 : order.getTempOpenCount() + 1);
                    order.transitTo(OrderStatus.TEMP_OPEN);
                }
                case CLOSE_VERIFY -> {
                    order.transitTo(order.getStatus() == OrderStatus.TEMP_OPEN
                            ? OrderStatus.ACTIVE : OrderStatus.STORED);
                    if (order.getStatus() == OrderStatus.STORED) {
                        // 格口从"预占"变"真有件"：状态分开存，事故时才能分辨件在不在柜里
                        if (slotMapper.markOccupied(order.getSlotId(), order.getId()) == 0) {
                            throw new BizException(ResultCode.SYSTEM_ERROR,
                                    "格口状态与订单不一致，需人工核对 slotId=" + order.getSlotId());
                        }
                        // 计费开始：时间一律用服务端时间（设备时钟不可信，S-07）
                        order.transitTo(OrderStatus.ACTIVE);
                        order.setStartedAt(LocalDateTime.now());
                        order.setExpectedFinishAt(order.getStartedAt()
                                .plusMinutes(order.getEstimateMinutes() == null ? 60 : order.getEstimateMinutes()));
                    }
                }
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
            // 关门校验失败：件已经在柜里，谎报与错乱目标都必须人工，绝不能再自动流转
            case CLOSE_VERIFY, FORCE_OPEN -> order.transitTo(OrderStatus.ABNORMAL);
            default -> order.transitTo(OrderStatus.ABNORMAL);
        }
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
