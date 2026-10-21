package com.wherelee.cabinet.storage.app;
import com.wherelee.cabinet.locker.api.CabinetSnapshot;
import com.wherelee.cabinet.locker.api.SiteSnapshot;
import com.wherelee.cabinet.locker.api.SlotQueryPort;
import com.wherelee.cabinet.locker.api.SlotSnapshot;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.funds.app.PricingPolicy;
import com.wherelee.cabinet.funds.app.PointAccountService;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.funds.domain.BizPointTxn;
import com.wherelee.cabinet.locker.domain.BizCabinet;
import com.wherelee.cabinet.locker.domain.BizCompartment;
import com.wherelee.cabinet.locker.domain.BizSite;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.OrderStatus;
import com.wherelee.cabinet.locker.api.CompartmentAnomaly;
import com.wherelee.cabinet.locker.api.SizeType;
import com.wherelee.cabinet.locker.infra.BizCabinetMapper;
import com.wherelee.cabinet.funds.infra.BizPointTxnMapper;
import com.wherelee.cabinet.locker.infra.BizCompartmentMapper;
import com.wherelee.cabinet.locker.infra.BizSiteMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户端的只读查询（第 15 刀接口层）：点位、点位下的暂存柜、我的订单、我的钱包。
 *
 * <p>为什么单独一层而不是让控制器直接查库：接口层不得碰 Mapper（ArchUnit 立着），
 * 而且这几张表之间的关系（订单→格口→柜机→点位）必须有一个地方**一次讲清楚**，
 * 否则每个端点各拼一遍 join，迟早出现"列表里的柜机编号和详情页不一样"。
 *
 * <p>查询都走批处理不用循环查库：一次订单列表最多三条 SQL（订单、格口、柜机），
 * 柜机与格口用 `in` 一次取回再在内存里对号。二十条历史单不值得发二十次查询，
 * 而点位列表下的柜机数也是同理（全部柜机取一次，按 siteId 分组）。
 *
 * <p>这里<b>不做任何资金与状态判定</b>：余额、欠费、能不能下单，判据都在 `OrderFundService`
 * 与 `StorageOrderService` 里，这一层只是把它们已经落库的事实读出来给人看。
 * 读出来的人和判的人若各算一遍，就会出现"后台说没欠、下单却被拒"这种口径分裂。
 */
@Service
public class StorageQueryService {

    /** 活动中的状态：这三档表示格子还归这名用户占着。 */
    private static final List<OrderStatus> ACTIVE_STATES =
            List.of(OrderStatus.RESERVED, OrderStatus.OPENING, OrderStatus.ACTIVE);

    private final SlotQueryPort slotQueries;
    private final BizStorageOrderMapper orderMapper;
    /** 账单口径的资金能力（流水、快照参数、算价）从这里取。 */
    private final com.wherelee.cabinet.storage.api.FundPort funds;

    public StorageQueryService(SlotQueryPort slotQueries, BizStorageOrderMapper orderMapper,
                               com.wherelee.cabinet.storage.api.FundPort funds) {
        this.slotQueries = slotQueries;
        this.orderMapper = orderMapper;
        this.funds = funds;
    }

    /**
     * 取件凭据（第 25 刀）：把码交给已登录的本人，只交活单。
     *
     * <p>终态单不回显：这与凭码路径 `pickAliveByVoucher` 只认活单是同一判据——结束后还给
     * 用户一个用不了的码，只会引来“我照着输了为什么开不了”的报障。
     */
    public com.wherelee.cabinet.storage.api.VoucherView voucher(Long customerId, String orderNo) {
        BizStorageOrder order = orderMapper.selectOne(Wrappers.<BizStorageOrder>lambdaQuery()
                .eq(BizStorageOrder::getOrderNo, orderNo));
        if (order == null || !order.getCustomerId().equals(customerId)) {
            // 与写路径同一口径：不区分“不存在”与“不是你的”，避免用 404/403 差值枚举他人单号
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "寄存单不存在");
        }
        if (order.getStatus().isTerminal()) {
            throw new BizException(ResultCode.BIZ_ERROR, "该单已结束，取件码已失效");
        }
        CabinetSnapshot cabinet = slotQueries.cabinet(order.getCabinetId()).orElse(null);
        SlotSnapshot slot = slotQueries.slot(order.getSlotId()).orElse(null);
        return new com.wherelee.cabinet.storage.api.VoucherView(order.getOrderNo(),
                cabinet == null ? "-" : cabinet.cabinetNo(),
                slot == null ? "-" : slot.slotNo(),
                order.getSizeType().name(), order.getStatus().name(),
                order.getVoucherCode(), order.getVoucherLockedAt() != null);
    }

    /** 点位：找地方需要的字段 + 各尺寸还剩多少正常格口（全异常就是 0）。 */
    public record SiteView(String siteCode, String name, String category, String address, long cabinetCount,
                           long freeSmall, long freeMedium, long freeLarge) {
    }

    /** 点位下的一台暂存柜。状态与在线情况原样读出来给人看，不在这里下判断。 */
    public record CabinetView(String cabinetNo, String name, String status, String onlineState) {
    }

    /**
     * 一张寄存单的可见信息（含柜机与格口编号，用户凭这两个字段才能在柜机前对上位置）。
     * leftoverRetrievable：这张单的格口正有他的遗留物待取回（第 21 刀，与开门校验同一判据）。
     */
    public record OrderView(String orderNo, String cabinetNo, String slotNo, String sizeType, String status,
                            LocalDateTime startedAt, LocalDateTime expectedFinishAt, LocalDateTime createTime,
                            long settledPoints, long arrearsPoints,
                            boolean leftoverRetrievable) {
    }

    /** 钱包：余额、冻结、押金栏（历史遗留栏，仍要如实读出来）、欠费合计与未支付笔数。 */
    public record WalletView(long points, long frozenPoints, long depositPoints, long arrearsPoints,
                             long unpaidOrders, long activeOrders) {
    }

    public List<SiteView> sites() {
        List<SiteSnapshot> all = slotQueries.sites();
        if (all.isEmpty()) {
            return List.of();
        }
        // 柜机取一次、可分配格口取一次，剩下的在内存里按点位聚合：
        // 每台柜机每个尺寸各发一次查询就是 180×3 次，那是把用户端的列表页当压测现场跑
        Map<Long, Long> cabinetSite = new java.util.HashMap<>();
        Map<Long, Long> perSite = new java.util.HashMap<>();
        for (CabinetSnapshot cabinet : slotQueries.cabinets()) {
            cabinetSite.put(cabinet.id(), cabinet.siteId());
            perSite.merge(cabinet.siteId(), 1L, Long::sum);
        }
        Map<Long, Map<SizeType, Long>> freeBySite = new java.util.HashMap<>();
        for (SlotSnapshot slot : slotQueries.assignableSlots()) {
            Long siteId = cabinetSite.get(slot.cabinetId());
            if (siteId == null || slot.sizeType() == null) {
                continue;
            }
            freeBySite.computeIfAbsent(siteId, key -> new java.util.EnumMap<>(SizeType.class))
                    .merge(SizeType.of(slot.sizeType()), 1L, Long::sum);
        }
        return all.stream()
                .map(site -> {
                    Map<SizeType, Long> free = freeBySite.getOrDefault(site.id(), Map.of());
                    return new SiteView(site.siteCode(), site.name(), site.category(), site.address(),
                            perSite.getOrDefault(site.id(), 0L),
                            free.getOrDefault(SizeType.SMALL, 0L),
                            free.getOrDefault(SizeType.MEDIUM, 0L),
                            free.getOrDefault(SizeType.LARGE, 0L));
                })
                .toList();
    }

    public List<CabinetView> cabinets(String siteCode) {
        SiteSnapshot site = slotQueries.siteByCode(siteCode).orElse(null);
        if (site == null) {
            throw new BizException(ResultCode.DATA_NOT_FOUND, "点位不存在：" + siteCode);
        }
        return slotQueries.cabinetsOfSite(site.id()).stream()
                .map(cabinet -> new CabinetView(cabinet.cabinetNo(), cabinet.name(),
                        cabinet.cabinetStatus(), cabinet.onlineState()))
                .toList();
    }

    /**
     * 我的寄存单：最近若干张，含进行中的那一张。
     *
     * <p>不包一层"只给活动单"：用户在小程序里既要能接着用当前那一格，也要能翻到自己上次存过哪里。
     */
    public List<OrderView> myOrders(Long customerId, int limit) {
        int size = Math.min(Math.max(limit, 1), 50);
        List<BizStorageOrder> orders = orderMapper.selectList(Wrappers.<BizStorageOrder>lambdaQuery()
                .eq(BizStorageOrder::getCustomerId, customerId)
                .orderByDesc(BizStorageOrder::getCreateTime)
                .last("limit " + size));
        if (orders.isEmpty()) {
            return List.of();
        }
        Set<Long> slotIds = orders.stream().map(BizStorageOrder::getSlotId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Set<Long> cabinetIds = orders.stream().map(BizStorageOrder::getCabinetId).filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        // 格口取全列：除了编号，“待取回”标记要读 anomaly 与 currentOrderId（第 21 刀取回闭环的入口）
        Map<Long, SlotSnapshot> slots = slotIds.isEmpty() ? Map.of()
                : slotQueries.slots(slotIds).stream()
                .collect(Collectors.toMap(SlotSnapshot::id, s -> s));
        Map<Long, String> slotNos = slots.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().slotNo()));
        Map<Long, String> cabinetNos = cabinetIds.isEmpty() ? Map.of()
                : slotQueries.cabinets(cabinetIds).stream()
                .collect(Collectors.toMap(CabinetSnapshot::id, CabinetSnapshot::cabinetNo));

        return orders.stream()
                .map(order -> {
                    SlotSnapshot slot = order.getSlotId() == null ? null : slots.get(order.getSlotId());
                    // “待取回” = 单已终态 且 它的格口正是遗留物状态且没人占用：
                    // 三个条件与 LeftoverRetrieveService 的开门校验同一判据，列表亮了按钮点进去才不会扑空
                    boolean retrievable = order.getStatus() != null && order.getStatus().isTerminal()
                            && slot != null && CompartmentAnomaly.CONTENT_LEFT.name().equals(slot.anomaly())
                            && slot.currentOrderId() == null;
                    return new OrderView(order.getOrderNo(),
                            cabinetNos.get(order.getCabinetId()), slotNos.get(order.getSlotId()),
                            order.getSizeType() == null ? null : order.getSizeType().name(),
                            order.getStatus() == null ? null : order.getStatus().name(),
                            order.getStartedAt(), order.getExpectedFinishAt(), order.getCreateTime(),
                            nvl(order.getSettledPoints()), nvl(order.getArrearsPoints()),
                            retrievable);
                })
                .toList();
    }

    /**
     * 我的钱包。`arrearsPoints` 与未支付笔数从单上汇总（欠费的账本身就记在单上，账户里没有这一栏），
     * 所以这里读的是**同一个来源**：下单被拦不拦，看的是这些单有没有欠费，不是另一个计数器。
     */
    public WalletView wallet(Long customerId) {
        // 新注册客户在首次入账前没有点账户：钱包是只读视图，如实返回 0，
        // 不能在这里抛 NPE（实测：新用户第一次打开钱包会得到 500）。
        var account = funds.accountOf(customerId);
        // 不能 limit 50：下单拦截用的是全量 sum（BizLockerConsoleMapper.sumCustomerArrears），
        // 这里少算一张就会出现“钱包说没欠、下单却被拒”。只取欠费列，笔数由结果条数给出。
        List<BizStorageOrder> unpaid = orderMapper.selectList(Wrappers.<BizStorageOrder>lambdaQuery()
                .select(BizStorageOrder::getArrearsPoints)
                .eq(BizStorageOrder::getCustomerId, customerId)
                .gt(BizStorageOrder::getArrearsPoints, 0L));
        long arrears = unpaid.stream().mapToLong(o -> nvl(o.getArrearsPoints())).sum();
        long activeOrders = orderMapper.selectCount(Wrappers.<BizStorageOrder>lambdaQuery()
                .eq(BizStorageOrder::getCustomerId, customerId)
                .in(BizStorageOrder::getStatus, ACTIVE_STATES));
        return new WalletView(account == null ? 0L : nvl(account.points()),
                account == null ? 0L : nvl(account.frozenPoints()),
                account == null ? 0L : nvl(account.depositPoints()),
                arrears, unpaid.size(), activeOrders);
    }

    private static long nvl(Long value) {
        return value == null ? 0L : value;
    }

    /** 一笔资金流水（用户能逐笔核对“钱去哪了”）。 */
    public record BillLine(String type, long amount, long balanceAfter, String remark, LocalDateTime at) {
    }

    /**
     * 一张单的账单明细：参数、时长、应收、预收、实收、欠费，以每一笔流水。
     *
     * <p>为什么单独给这个接口：用户被“有未支付订单不许再下单”拦住时，现在只能看到一句结论，
     * 看不到这笔钱怎么来的。拦人的动作必须同时给出一条能自己查清的出口。
     *
     * <p><b>不拿当前价重算</b>：参数只从这张单的快照里读（同结算侧的约定）。
     * 另外“应收”算完还要跟单上记的实收对一下：两处不一致就说明结算与明细用了不同口径，
     * 这种事必须让接口自己报出来，不能等用户来对账。
     */
    public BillView bill(Long customerId, String orderNo) {
        BizStorageOrder order = orderMapper.selectOne(Wrappers.<BizStorageOrder>lambdaQuery()
                .eq(BizStorageOrder::getOrderNo, orderNo));
        if (order == null || !java.util.Objects.equals(order.getCustomerId(), customerId)) {
            throw new BizException(ResultCode.DATA_NOT_FOUND, "没找到这张寄存单");
        }
        return billOf(order);
    }

    /**
     * 按单构账单（<b>不校验归属</b>）。后台订单详情与用户端账单共用这一份口径：
     * 两边各算一遍，迟早出现“后台说欠 3 块、用户看到欠 5 块”。
     *
     * <p>“这张单该不该给你看”由调用方负责：用户端按 customerId 校验，后台按权限点放行。
     */
    public BillView billOf(BizStorageOrder order) {
        LocalDateTime end = order.getFinishedAt() != null ? order.getFinishedAt() : LocalDateTime.now();
        long minutes = order.getStartedAt() == null ? 0L
                : Math.max(0L, Duration.between(order.getStartedAt(), end).toMinutes());
        var params = funds.priceParamsOf(order.getPricingSnapshot());
        var quote = funds.quoteOf(order.getPricingSnapshot(), minutes);
        // ref_type 不能省：idx_txn_ref 是 (ref_type, ref_id)，只给 ref_id 用不上索引，
        // 还会把其他单据类型下恰好同 id 的流水混进来。
        java.util.List<BillLine> lines = funds.txnsOf(order.getId(), 60).stream()
                .map(t -> new BillLine(t.bizType(), t.amount(), t.balanceAfter(), t.remark(), t.createTime()))
                .toList();
        return new BillView(order.getOrderNo(),
                order.getStatus() == null ? null : order.getStatus().name(),
                order.getSizeType() == null ? null : order.getSizeType().name(),
                order.getStartedAt(), order.getFinishedAt(), minutes,
                params.unitPointsPerHour(), params.freeMinutes(), params.dailyCapHours(), params.capDays(),
                quote.billedHours(), quote.consumePoints(),
                nvl(order.getPrepayPoints()), nvl(order.getSettledPoints()), nvl(order.getArrearsPoints()),
                lines);
    }

    /** 账单明细视图。金额全部是点（1 点 = 1 分），不在此处换算成元。 */
    public record BillView(String orderNo, String status, String sizeType,
                           LocalDateTime startedAt, LocalDateTime finishedAt, long billedMinutes,
                           long unitPointsPerHour, int freeMinutes, int dailyCapHours, int capDays,
                           int billedHours, long chargePoints,
                           long prepayPoints, long settledPoints, long arrearsPoints,
                           java.util.List<BillLine> lines) {
    }
}
