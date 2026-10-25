package com.wherelee.cabinet.storage.app;
import com.wherelee.cabinet.locker.api.CabinetSnapshot;
import com.wherelee.cabinet.locker.api.SiteSnapshot;
import com.wherelee.cabinet.locker.api.SlotQueryPort;
import com.wherelee.cabinet.locker.api.SlotSnapshot;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.OrderStatus;
import com.wherelee.cabinet.locker.api.CompartmentAnomaly;
import com.wherelee.cabinet.locker.api.SizeType;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class StorageQueryService implements com.wherelee.cabinet.storage.api.StorageQueryPort {

    private static final List<OrderStatus> ACTIVE_STATES =
            List.of(OrderStatus.RESERVED, OrderStatus.OPENING, OrderStatus.ACTIVE);

    private final SlotQueryPort slotQueries;
    private final BizStorageOrderMapper orderMapper;
    private final com.wherelee.cabinet.storage.api.FundPort funds;

    public StorageQueryService(SlotQueryPort slotQueries, BizStorageOrderMapper orderMapper,
                               com.wherelee.cabinet.storage.api.FundPort funds) {
        this.slotQueries = slotQueries;
        this.orderMapper = orderMapper;
        this.funds = funds;
    }

    public com.wherelee.cabinet.storage.api.VoucherView voucher(Long customerId, String orderNo) {
        BizStorageOrder order = orderMapper.selectOne(Wrappers.<BizStorageOrder>lambdaQuery()
                .eq(BizStorageOrder::getOrderNo, orderNo));
        if (order == null || !order.getCustomerId().equals(customerId)) {
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

    public List<SiteView> sites() {
        List<SiteSnapshot> all = slotQueries.sites();
        if (all.isEmpty()) {
            return List.of();
        }
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

    public WalletView wallet(Long customerId) {
        var account = funds.accountOf(customerId);
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

    public BillView bill(Long customerId, String orderNo) {
        BizStorageOrder order = orderMapper.selectOne(Wrappers.<BizStorageOrder>lambdaQuery()
                .eq(BizStorageOrder::getOrderNo, orderNo));
        if (order == null || !java.util.Objects.equals(order.getCustomerId(), customerId)) {
            throw new BizException(ResultCode.DATA_NOT_FOUND, "没找到这张寄存单");
        }
        return billOf(order);
    }

    public BillView billOf(BizStorageOrder order) {
        LocalDateTime end = order.getFinishedAt() != null ? order.getFinishedAt() : LocalDateTime.now();
        long minutes = order.getStartedAt() == null ? 0L
                : Math.max(0L, Duration.between(order.getStartedAt(), end).toMinutes());
        var params = funds.priceParamsOf(order.getPricingSnapshot());
        var quote = funds.quoteOf(order.getPricingSnapshot(), minutes);
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

}
