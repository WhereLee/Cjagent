package com.wherelee.cabinet.application.storage;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.application.storage.dto.CreateOrderCommand;
import com.wherelee.cabinet.application.storage.dto.StorageOrderView;
import com.wherelee.cabinet.application.storage.message.OrderPersistMessage;
import com.wherelee.cabinet.application.support.ConsumeIdempotencyGuard;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.context.TenantContext;
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
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 预扣 + 异步落库的寄存路径（策略 {@code prealloc}）。
 *
 * <p><b>与同步路径的语义差别要说清</b>：同步路径里"占位成功"等于"DB 里有单"；
 * 这里"占位成功"只等于"格口名额已从 Redis 空闲集合弹出、消息已投递"，
 * 订单行还要等消费者写进去。返回给前端的 {@code status=PERSIST_QUEUED} 就是这个意思
 * ——<b>不能用它骗用户"已就绪"</b>。
 *
 * <p>换来的是：请求路径上<b>没有数据库事务</b>，热点行竞争被 Redis 的单线程原子性吸收，
 * 这正是第 9 刀压测算出的瓶颈（连接池 10 × 事务 1.6s）的解法。
 *
 * <p>三条失败路径的处理各不相同，是这套设计的重点：
 * <ul>
 *   <li>消息发送失败 → 当场归还预扣并返回 503（用户重试即可，没有幽灵占位）；</li>
 *   <li>消费落库失败（格口被占/DB 异常）→ 抛出让 broker 重试，最终进死信由人处理；</li>
 *   <li>进程在预扣与发送之间崩溃 → hold 标记 TTL 到期，格口悬空（少卖），靠重新同步收敛。</li>
 * </ul>
 */
@Service
public class AsyncStorageOrderAppService {

    private static final Logger log = LoggerFactory.getLogger(AsyncStorageOrderAppService.class);

    /** 尚未落库的中间态，只出现在响应里，<b>不是</b> OrderStatus 的成员（DB 里此时没有行）。 */
    public static final String STATUS_QUEUED = "PERSIST_QUEUED";

    private final BizCabinetMapper cabinetMapper;
    private final BizCompartmentMapper slotMapper;
    private final BizStorageOrderMapper orderMapper;
    private final SlotPreDeductionService preDeduction;
    private final OrderPersistPublisher publisher;
    private final ConsumeIdempotencyGuard guard;
    private final com.wherelee.cabinet.application.point.OrderFundService funds;

    @Value("${cabinet.alloc.hold-ttl:180s}")
    private Duration holdTtl;

    @Value("${cabinet.mq.order-persist-topic:" + OrderPersistMessage.TOPIC_DEFAULT + "}")
    private String topic;

    public AsyncStorageOrderAppService(BizCabinetMapper cabinetMapper,
                                       BizCompartmentMapper slotMapper,
                                       BizStorageOrderMapper orderMapper,
                                       SlotPreDeductionService preDeduction,
                                       OrderPersistPublisher publisher,
                                       ConsumeIdempotencyGuard guard,
                                       com.wherelee.cabinet.application.point.OrderFundService funds) {
        this.cabinetMapper = cabinetMapper;
        this.slotMapper = slotMapper;
        this.orderMapper = orderMapper;
        this.preDeduction = preDeduction;
        this.publisher = publisher;
        this.guard = guard;
        this.funds = funds;
    }

    public StorageOrderView createAsync(Long customerId, CreateOrderCommand command) {
        BizCabinet cabinet = cabinetMapper.selectOne(Wrappers.<BizCabinet>lambdaQuery()
                .eq(BizCabinet::getCabinetNo, command.cabinetNo()));
        if (cabinet == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "柜机不存在：" + command.cabinetNo());
        }
        if (cabinet.getCabinetStatus() != CabinetStatus.ENABLED) {
            throw new BizException(ResultCode.BIZ_ERROR, "该柜机暂停服务，请选择邻近柜机");
        }

        SizeType required = parseSize(command.sizeType());
        List<SizeType> preference = SizeType.acceptanceOrder(required);

        Long orderId = IdWorker.getId();
        String orderNo = "SO" + orderId;

        SlotPreDeductionService.PreDeduct deducted =
                preDeduction.tryPreDeduct(cabinet.getId(), preference, orderNo, holdTtl, customerId);
        if (deducted == null) {
            // 预扣拿不到就不落任何 DB 写：这条路径的意义正是让 DB 不在关键路径上
            throw new BizException(ResultCode.SLOT_UNAVAILABLE, "该柜机没有可用格口");
        }

        String pricingSnapshot = "{\"strategy\":\"placeholder\",\"estimateMinutes\":"
                + command.estimateMinutes() + ",\"sizeType\":\"" + deducted.size() + "\"}";
        String voucher = voucherCode();
        OrderPersistMessage message = new OrderPersistMessage(orderNo, orderId, cabinet.getTenantId(),
                customerId, cabinet.getId(), deducted.slotId(), deducted.size().name(), voucher,
                command.estimateMinutes(), pricingSnapshot, command.requestId(), MDC.get("traceId"));

        try {
            publisher.publish(message);
        } catch (RuntimeException e) {
            // 发送失败必须当场归还：否则这个格口对谁都不开放，而用户手里没有任何单号
            preDeduction.release(deducted, orderNo);
            throw e;
        }

        return new StorageOrderView(orderNo, cabinet.getCabinetNo(), slotNoOf(deducted.slotId()),
                deducted.size().name(), STATUS_QUEUED, command.estimateMinutes(), 1, "prealloc");
    }

    /**
     * 消费者入口：幂等落库。异常一律上抛，交给 broker 重试（最终进死信）。
     *
     * <p><b>租户上下文在本方法内部建立</b>（消费线程没有 HTTP 请求，不建上下文则租户守卫会拒写
     * 或写错租户）。幂等判定与落库都在同一个上下文里，否则测试从外面包一层 runAs 会把
     * “自己建上下文”这条真实行为遮住——第 10 刀就因此假绿过。
     *
     * <p><b>信任边界要写清</b>：tenant_id 来自消息体，意味着“谁能往这个 topic 写”就是谁能以哪个租户身份落库。
     * 本项目的 topic 只由服务自己产生，且 broker 不得对外网开放（第 16 刀的 ACL/ TLS 负这个责）。
     */
    public void persistFromMessage(OrderPersistMessage message) {
        String previousTrace = MDC.get("traceId");
        if (message.traceId() != null) {
            MDC.put("traceId", message.traceId());
        }
        try {
            TenantContext.runAs(message.tenantId(), () -> {
                ConsumeIdempotencyGuard.Decision decision = guard.begin(topic, message.msgKey(),
                        message.tenantId(), message.traceId());
                if (decision == ConsumeIdempotencyGuard.Decision.SKIP) {
                    return;
                }
                try {
                    doPersist(message);
                    guard.markProcessed(topic, message.msgKey());
                } catch (RuntimeException e) {
                    guard.markFailed(topic, message.msgKey(), e.getMessage(), message.traceId());
                    throw e;
                }
            });
        } finally {
            if (previousTrace != null) {
                MDC.put("traceId", previousTrace);
            } else {
                MDC.remove("traceId");
            }
        }
    }

    private void doPersist(OrderPersistMessage message) {
        // 占位标记不在 = 已被取消或已悬空过期：此时落库会造出一张“格口已放回自由集合”的孤儿单
        if (!preDeduction.hasHold(message.orderNo())) {
            log.info("占位已释放，不落库 orderNo={}（取消先于落库或租约到期）", message.orderNo());
            return;
        }
        BizCabinet cabinet = cabinetMapper.selectById(message.cabinetId());
        if (cabinet == null) {
            throw new BizException(ResultCode.SYSTEM_ERROR,
                    "预扣指向的柜机不存在 cabinetId=" + message.cabinetId());
        }
        BizCompartment slot = slotMapper.selectById(message.slotId());
        if (slot == null || !slot.getCabinetId().equals(message.cabinetId())) {
            throw new BizException(ResultCode.SYSTEM_ERROR,
                    "预扣指向的格口不存在或归属不符 slotId=" + message.slotId());
        }

        int hit = slotMapper.reserveSlot(slot.getId(), message.orderId());
        if (hit == 0) {
            // 影响 0 行有两种可能：已经被自己占过（重投，幂等放行），或被别人占了（必须失败让人看）
            boolean mine = message.orderId().equals(slot.getCurrentOrderId());
            if (!mine) {
                // 别人已占：把这笔预扣就此 terminate（删标记不回集合，因为格口确实已不空闲），
                // 然后报错让人看——静默改写 slotId 会把用户领到另一个格口，那比失败更糟
                preDeduction.commit(preDeduction.rehydrate(message.cabinetId(),
                        SizeType.of(message.sizeType()), message.slotId()), message.orderNo());
                throw new BizException(ResultCode.SYSTEM_ERROR,
                        "格口已被占用，需要人工核对 slotId=" + message.slotId() + " orderNo=" + message.orderNo());
            }
        }

        BizStorageOrder order = buildOrder(message, cabinet);
        try {
            // 先算钱再插单（与同步路径一致）：补一次 updateById 会因为 @Version 影响 0 行而静默丢快照
            funds.holdFunds(order, message.estimateMinutes() == null ? 60 : message.estimateMinutes());
            orderMapper.insert(order);
        } catch (DuplicateKeyException e) {
            // 订单号唯一索引挡住重复落库：这是"已处理"而不是失败，不能让它进死信
            log.info("订单已存在，视为重复投递 orderNo={}", message.orderNo());
        }

        preDeduction.commit(preDeduction.rehydrate(message.cabinetId(),
                SizeType.of(message.sizeType()), message.slotId()), message.orderNo());
    }

    /**
     * 取消“已预扣但未落库”的单（异步路径特有的赛跑）。
     *
     * <p>此时 DB 里没有行，<b>归属只能靠 hold 里存客户 ID 校验</b>；校验不了就不能取消，
     * 否则任何知道单号的人都能把别人的占位释放掉。
     *
     * @return true 表示确实把占位归还了；false 表示没有占位标记（已落库或已过期）
     */
    public boolean cancelQueued(String orderNo, Long customerId) {
        Map<String, String> hold = preDeduction.holdOf(orderNo);
        if (hold.isEmpty()) {
            return false;
        }
        if (!String.valueOf(customerId).equals(hold.get("cust"))) {
            // 不区分“不是你的单”与“单不存在”，避免用返回差值枚举他人单号
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "寄存单不存在");
        }
        return preDeduction.releaseByKey(hold.get("key"), orderNo, Long.valueOf(hold.get("slot")));
    }

    private BizStorageOrder buildOrder(OrderPersistMessage message, BizCabinet cabinet) {
        BizStorageOrder order = new BizStorageOrder();
        order.setId(message.orderId());
        order.setOrderNo(message.orderNo());
        order.setSiteId(cabinet.getSiteId());
        order.setCabinetId(message.cabinetId());
        order.setSlotId(message.slotId());
        order.setSizeType(SizeType.of(message.sizeType()));
        order.setCustomerId(message.customerId());
        order.setVoucherCode(message.voucherCode());
        order.setEstimateMinutes(message.estimateMinutes());
        order.setTempOpenCount(0);
        order.setDepositPoints(0L);
        order.setFrozenPoints(0L);
        order.setSettledPoints(0L);
        order.setArrearsPoints(0L);
        order.setPricingSnapshot(message.pricingSnapshot());
        order.initStatus();
        order.transitTo(OrderStatus.RESERVED);
        return order;
    }

    private SizeType parseSize(String value) {
        try {
            return SizeType.of(value);
        } catch (IllegalArgumentException e) {
            throw new BizException(ResultCode.PARAM_INVALID, "尺寸不合法：" + value);
        }
    }

    private String voucherCode() {
        return String.format("V%06d", ThreadLocalRandom.current().nextInt(100000, 1000000));
    }

    private String slotNoOf(Long slotId) {
        BizCompartment slot = slotMapper.selectById(slotId);
        return slot == null ? "-" : slot.getSlotNo();
    }

    /**
     * 测试与运维入口：把 DB 真相同步进 Redis 空闲集合（第 13 刀的定时校准调它）。
     *
     * <p>集合同样只收“真的可卖”的格口（共用 SlotCandidateQuery）：预扣只认集合不查 DB，
     * 所以“门开着”或“有遗留物”的格口一旦进了集合，就会被真的分给用户——
     * 这是预扣设计下<b>唯一能造成卖错</b>的地方（其他策略都会在条件 UPDATE 上被拦下）。
     */
    public void syncFreeSets(Long cabinetId) {
        for (SizeType size : SizeType.values()) {
            List<Long> freeIds = slotMapper.selectList(SlotCandidateQuery.assignable(cabinetId, size))
                    .stream().map(BizCompartment::getId).toList();
            preDeduction.replaceFreeSet(cabinetId, size, freeIds);
        }
        log.info("空闲格口集合已同步 cabinetId={}", cabinetId);
    }
}
