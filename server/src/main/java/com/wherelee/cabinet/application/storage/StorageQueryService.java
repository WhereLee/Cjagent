package com.wherelee.cabinet.application.storage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.application.point.PointAccountService;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.entity.BizCabinet;
import com.wherelee.cabinet.domain.entity.BizCompartment;
import com.wherelee.cabinet.domain.entity.BizSite;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.OrderStatus;
import com.wherelee.cabinet.infrastructure.mapper.BizCabinetMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizCompartmentMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizSiteMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import org.springframework.stereotype.Service;

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

    private final BizSiteMapper siteMapper;
    private final BizCabinetMapper cabinetMapper;
    private final BizCompartmentMapper slotMapper;
    private final BizStorageOrderMapper orderMapper;
    private final PointAccountService accounts;

    public StorageQueryService(BizSiteMapper siteMapper, BizCabinetMapper cabinetMapper,
                               BizCompartmentMapper slotMapper, BizStorageOrderMapper orderMapper,
                               PointAccountService accounts) {
        this.siteMapper = siteMapper;
        this.cabinetMapper = cabinetMapper;
        this.slotMapper = slotMapper;
        this.orderMapper = orderMapper;
        this.accounts = accounts;
    }

    /** 点位：只给找得到地方需要的字段，不给内部 ID。 */
    public record SiteView(String siteCode, String name, String category, String address, long cabinetCount) {
    }

    /** 点位下的一台暂存柜。状态与在线情况原样读出来给人看，不在这里下判断。 */
    public record CabinetView(String cabinetNo, String name, String status, String onlineState) {
    }

    /** 一张寄存单的可见信息（含柜机与格口编号，用户凭这两个字段才能在柜机前对上位置）。 */
    public record OrderView(String orderNo, String cabinetNo, String slotNo, String sizeType, String status,
                            LocalDateTime startedAt, LocalDateTime expectedFinishAt, LocalDateTime createTime,
                            long settledPoints, long arrearsPoints) {
    }

    /** 钱包：余额、冻结、押金栏（历史遗留栏，仍要如实读出来）、欠费合计与未支付笔数。 */
    public record WalletView(long points, long frozenPoints, long depositPoints, long arrearsPoints,
                             long unpaidOrders, long activeOrders) {
    }

    public List<SiteView> sites() {
        List<BizSite> all = siteMapper.selectList(Wrappers.<BizSite>lambdaQuery().orderByAsc(BizSite::getSiteCode));
        if (all.isEmpty()) {
            return List.of();
        }
        // 全部柜机取一次再分组：每台柜机一次 count 查询就是 N+1
        Map<Long, Long> perSite = cabinetMapper.selectList(Wrappers.<BizCabinet>lambdaQuery()
                        .select(BizCabinet::getSiteId))
                .stream()
                .collect(Collectors.groupingBy(BizCabinet::getSiteId, Collectors.counting()));
        return all.stream()
                .map(site -> new SiteView(site.getSiteCode(), site.getName(), site.getCategory(), site.getAddress(),
                        perSite.getOrDefault(site.getId(), 0L)))
                .toList();
    }

    public List<CabinetView> cabinets(String siteCode) {
        BizSite site = siteMapper.selectOne(Wrappers.<BizSite>lambdaQuery().eq(BizSite::getSiteCode, siteCode));
        if (site == null) {
            throw new BizException(ResultCode.DATA_NOT_FOUND, "点位不存在：" + siteCode);
        }
        return cabinetMapper.selectList(Wrappers.<BizCabinet>lambdaQuery()
                        .eq(BizCabinet::getSiteId, site.getId())
                        .orderByAsc(BizCabinet::getCabinetNo))
                .stream()
                .map(cabinet -> new CabinetView(cabinet.getCabinetNo(), cabinet.getName(),
                        cabinet.getCabinetStatus() == null ? null : cabinet.getCabinetStatus().name(),
                        cabinet.getOnlineState() == null ? null : cabinet.getOnlineState().name()))
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
        Map<Long, String> slotNos = slotIds.isEmpty() ? Map.of()
                : slotMapper.selectList(Wrappers.<BizCompartment>lambdaQuery()
                        .select(BizCompartment::getId, BizCompartment::getSlotNo)
                        .in(BizCompartment::getId, slotIds))
                .stream().collect(Collectors.toMap(BizCompartment::getId, BizCompartment::getSlotNo));
        Map<Long, String> cabinetNos = cabinetIds.isEmpty() ? Map.of()
                : cabinetMapper.selectList(Wrappers.<BizCabinet>lambdaQuery()
                        .select(BizCabinet::getId, BizCabinet::getCabinetNo)
                        .in(BizCabinet::getId, cabinetIds))
                .stream().collect(Collectors.toMap(BizCabinet::getId, BizCabinet::getCabinetNo));

        return orders.stream()
                .map(order -> new OrderView(order.getOrderNo(),
                        cabinetNos.get(order.getCabinetId()), slotNos.get(order.getSlotId()),
                        order.getSizeType() == null ? null : order.getSizeType().name(),
                        order.getStatus() == null ? null : order.getStatus().name(),
                        order.getStartedAt(), order.getExpectedFinishAt(), order.getCreateTime(),
                        nvl(order.getSettledPoints()), nvl(order.getArrearsPoints())))
                .toList();
    }

    /**
     * 我的钱包。`arrearsPoints` 与未支付笔数从单上汇总（欠费的账本身就记在单上，账户里没有这一栏），
     * 所以这里读的是**同一个来源**：下单被拦不拦，看的是这些单有没有欠费，不是另一个计数器。
     */
    public WalletView wallet(Long customerId) {
        var account = accounts.accountOf(customerId);
        List<BizStorageOrder> unpaid = orderMapper.selectList(Wrappers.<BizStorageOrder>lambdaQuery()
                .eq(BizStorageOrder::getCustomerId, customerId)
                .gt(BizStorageOrder::getArrearsPoints, 0L)
                .last("limit 50"));
        long arrears = unpaid.stream().mapToLong(o -> nvl(o.getArrearsPoints())).sum();
        long activeOrders = orderMapper.selectCount(Wrappers.<BizStorageOrder>lambdaQuery()
                .eq(BizStorageOrder::getCustomerId, customerId)
                .in(BizStorageOrder::getStatus, ACTIVE_STATES));
        return new WalletView(nvl(account.getPoints()), nvl(account.getFrozenPoints()),
                nvl(account.getDepositPoints()), arrears, unpaid.size(), activeOrders);
    }

    private static long nvl(Long value) {
        return value == null ? 0L : value;
    }
}
