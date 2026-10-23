package com.wherelee.cabinet.storage.app;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wherelee.cabinet.platform.web.PageSupport;
import com.wherelee.cabinet.common.api.PageQuery;
import com.wherelee.cabinet.common.api.PageResult;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.locker.api.CabinetSnapshot;
import com.wherelee.cabinet.locker.api.SiteSnapshot;
import com.wherelee.cabinet.locker.api.SlotQueryPort;
import com.wherelee.cabinet.locker.api.SlotSnapshot;
import com.wherelee.cabinet.identity.api.CustomerQueryPort;
import com.wherelee.cabinet.identity.api.CustomerSnapshot;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.OrderStatus;
import com.wherelee.cabinet.device.api.CommandPort;
import com.wherelee.cabinet.device.api.EvidencePort;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 后台订单查询（只读）。
 *
 * <p>补的是运营台缺的第一块：客服接到的第一句话通常是“我的订单号是 xxx，东西取不出来”，
 * 而在此之前所有后台页面都假设“你从异常或工单进来”，只能靠格口反查当前单。
 *
 * <p>这一层<b>不做任何判定</b>：状态、金额、异常都是把已经落库的事实读出来；
 * “能不能结束”“该不该退钱”仍由 StorageOrderService 与工单侧决定。
 * 读的人和判的人各算一遍，就会出现“后台说没欠、下单却被拒”这种口径分裂。
 *
 * <p>列表按页批量取名字（客户/柜机/格口/点位各一次 {@code in}），不循环查库；
 * 详情固定六条等值查询（单/客户/账单/指令/证据/工单），不引入缓存。
 */
@Service
public class AdminOrderQueryService implements com.wherelee.cabinet.storage.api.AdminOrderConsolePort {

    /** 允许前端自定义排序的字段（PageSupport 白名单）。 */
    private static final Set<String> ORDER_SORTABLE = Set.of("createTime", "finishedAt");

    private static final int COMMAND_LIMIT = 50;
    private static final int EVIDENCE_LIMIT = 50;
    private static final int RELATED_WORK_ORDER_LIMIT = 20;

    private final BizStorageOrderMapper orderMapper;
    private final CustomerQueryPort customers;
    private final SlotQueryPort slotQueries;
    private final CommandPort commandPort;
    private final EvidencePort evidencePort;
    private final com.wherelee.cabinet.servicedesk.api.WorkOrderPort workOrderPort;
    private final StorageQueryService storageQueries;

    public AdminOrderQueryService(BizStorageOrderMapper orderMapper,
                                  CustomerQueryPort customers,
                                  SlotQueryPort slotQueries,
                                  CommandPort commandPort,
                                  EvidencePort evidencePort,
                                  com.wherelee.cabinet.servicedesk.api.WorkOrderPort workOrderPort,
                                  StorageQueryService storageQueries) {
        this.orderMapper = orderMapper;
        this.customers = customers;
        this.slotQueries = slotQueries;
        this.commandPort = commandPort;
        this.evidencePort = evidencePort;
        this.workOrderPort = workOrderPort;
        this.storageQueries = storageQueries;
    }

    public PageResult<OrderRow> list(String orderNo, String phone, OrderStatus status,
                                     LocalDate from, LocalDate to, PageQuery query) {
        var wrapper = Wrappers.<BizStorageOrder>lambdaQuery();
        if (StringUtils.hasText(orderNo)) {
            wrapper.eq(BizStorageOrder::getOrderNo, orderNo.trim());
        }
        if (StringUtils.hasText(phone)) {
            List<Long> customerIds = customers.idsByPhone(phone.trim());
            if (customerIds.isEmpty()) {
                return PageResult.empty(query.getPageNum(), query.getPageSize());
            }
            wrapper.in(BizStorageOrder::getCustomerId, customerIds);
        }
        if (status != null) {
            wrapper.eq(BizStorageOrder::getStatus, status);
        }
        if (from != null) {
            wrapper.ge(BizStorageOrder::getCreateTime, from.atStartOfDay());
        }
        if (to != null) {
            wrapper.lt(BizStorageOrder::getCreateTime, to.plusDays(1).atStartOfDay());
        }
        if (!query.hasSort()) {
            wrapper.orderByDesc(BizStorageOrder::getCreateTime);
        }

        Page<BizStorageOrder> page = PageSupport.toPage(query, ORDER_SORTABLE);
        IPage<BizStorageOrder> result = orderMapper.selectPage(page, wrapper);
        return PageResult.of(result.getCurrent(), result.getSize(), result.getTotal(),
                result.getPages(), toRows(result.getRecords()));
    }

    /** 按单号取详情；跨租户查不到与不存在同一处理（不暴露“别家有这么一张单”）。 */
    public OrderDetail detail(String orderNo) {
        BizStorageOrder order = orderMapper.selectOne(Wrappers.<BizStorageOrder>lambdaQuery()
                .eq(BizStorageOrder::getOrderNo, orderNo));
        if (order == null) {
            throw new BizException(ResultCode.DATA_NOT_FOUND, "没找到这张寄存单");
        }
        CustomerSnapshot customer = order.getCustomerId() == null
                ? null : customers.byId(order.getCustomerId()).orElse(null);
        CabinetSnapshot cabinet = order.getCabinetId() == null
                ? null : slotQueries.cabinet(order.getCabinetId()).orElse(null);
        SlotSnapshot slot = order.getSlotId() == null
                ? null : slotQueries.slot(order.getSlotId()).orElse(null);
        SiteSnapshot site = cabinet == null || cabinet.siteId() == null
                ? null : slotQueries.site(cabinet.siteId()).orElse(null);

        List<CommandLine> commands = commandPort.byOrder(order.getId(), COMMAND_LIMIT).stream()
                .map(c -> new CommandLine(c.action(), c.state(), c.retryCount(),
                        c.sentAt(), c.ackedAt(), c.lastError()))
                .toList();

        List<EvidenceLine> evidence = evidencePort.byOrder(order.getId(), EVIDENCE_LIMIT).stream()
                .map(e -> new EvidenceLine(e.source(), e.presence(), e.moment(),
                        e.confidence(), e.aiLabel(), e.note(), e.at()))
                .toList();

        List<WorkOrderLine> workOrders = workOrderPort.byStorageId(order.getId(), RELATED_WORK_ORDER_LIMIT)
                .stream()
                .map(w -> new WorkOrderLine(w.id(), w.status(), w.category(), w.priority(),
                        w.closeReason(), w.title(), w.createTime()))
                .toList();

        return new OrderDetail(toRow(order, customer, cabinet, slot, site),
                customer == null ? null : customer.phone(),
                name(order.getCloseReason()), name(order.getDisputeState()),
                nvl(order.getAiReviewCount()), nvl(order.getTempOpenCount()), nvl(order.getVoucherWrongCount()),
                order.getVoucherLockedAt() != null, order.getLeftoverReportedAt() != null,
                nvl(order.getDepositPoints()), nvl(order.getRemoteClosePoints()),
                // 历史单没有定价快照时账单区为 null：已落库的预收/实收/欠费照常展示，
                // 但不能因为一张旧单拼不出明细就把整个详情页打挂（用户端账单仍维持原口径）。
                StringUtils.hasText(order.getPricingSnapshot()) ? storageQueries.billOf(order) : null,
                commands, evidence, workOrders);
    }

    /** 列表行批量补齐名字：四张关联表各一次 in 查询，禁止按行查库。 */
    private List<OrderRow> toRows(List<BizStorageOrder> orders) {
        if (orders.isEmpty()) {
            return List.of();
        }
        Set<Long> customerIds = orders.stream().map(BizStorageOrder::getCustomerId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> cabinetIds = orders.stream().map(BizStorageOrder::getCabinetId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> slotIds = orders.stream().map(BizStorageOrder::getSlotId)
                .filter(Objects::nonNull).collect(Collectors.toSet());

        Map<Long, CustomerSnapshot> customersById = customerIds.isEmpty() ? Map.of()
                : customers.byIds(customerIds).stream()
                .collect(Collectors.toMap(CustomerSnapshot::id, c -> c));
        Map<Long, CabinetSnapshot> cabinets = cabinetIds.isEmpty() ? Map.of()
                : slotQueries.cabinets(cabinetIds).stream()
                .collect(Collectors.toMap(CabinetSnapshot::id, c -> c));
        Map<Long, SlotSnapshot> slots = slotIds.isEmpty() ? Map.of()
                : slotQueries.slots(slotIds).stream()
                .collect(Collectors.toMap(SlotSnapshot::id, s -> s));
        Set<Long> siteIds = cabinets.values().stream().map(CabinetSnapshot::siteId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, SiteSnapshot> sites = siteIds.isEmpty() ? Map.of()
                : slotQueries.sites().stream().filter(s -> siteIds.contains(s.id()))
                .collect(Collectors.toMap(SiteSnapshot::id, s -> s));

        return orders.stream()
                .map(o -> {
                    CabinetSnapshot cabinet = cabinets.get(o.getCabinetId());
                    SiteSnapshot site = cabinet == null || cabinet.siteId() == null
                            ? null : sites.get(cabinet.siteId());
                    return toRow(o, customersById.get(o.getCustomerId()), cabinet,
                            slots.get(o.getSlotId()), site);
                })
                .toList();
    }

    private static OrderRow toRow(BizStorageOrder order, CustomerSnapshot customer, CabinetSnapshot cabinet,
                                  SlotSnapshot slot, SiteSnapshot site) {
        return new OrderRow(order.getId(), order.getOrderNo(),
                name(order.getStatus()), name(order.getSizeType()),
                order.getCustomerId(), customer == null ? null : customer.nickname(),
                site == null ? null : site.name(),
                cabinet == null ? null : cabinet.cabinetNo(),
                slot == null ? null : slot.slotNo(),
                nvl(order.getPrepayPoints()), nvl(order.getSettledPoints()), nvl(order.getArrearsPoints()),
                order.getCreateTime(), order.getStartedAt(), order.getFinishedAt());
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static long nvl(Long value) {
        return value == null ? 0L : value;
    }

    private static int nvl(Integer value) {
        return value == null ? 0 : value;
    }
}
