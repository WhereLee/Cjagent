package com.wherelee.cabinet.application.storage;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wherelee.cabinet.platform.web.PageSupport;
import com.wherelee.cabinet.common.api.PageQuery;
import com.wherelee.cabinet.common.api.PageResult;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.entity.BizCabinet;
import com.wherelee.cabinet.domain.entity.BizCompartment;
import com.wherelee.cabinet.domain.entity.BizCustomer;
import com.wherelee.cabinet.device.domain.BizDeviceCommand;
import com.wherelee.cabinet.device.domain.BizItemEvidence;
import com.wherelee.cabinet.domain.entity.BizSite;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.entity.BizWorkOrder;
import com.wherelee.cabinet.domain.enums.OrderStatus;
import com.wherelee.cabinet.infrastructure.mapper.BizCabinetMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizCompartmentMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizCustomerMapper;
import com.wherelee.cabinet.device.infra.BizDeviceCommandMapper;
import com.wherelee.cabinet.device.infra.BizItemEvidenceMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizSiteMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizWorkOrderMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
public class AdminOrderQueryService {

    /** 允许前端自定义排序的字段（PageSupport 白名单）。 */
    private static final Set<String> ORDER_SORTABLE = Set.of("createTime", "finishedAt");

    private static final int COMMAND_LIMIT = 50;
    private static final int EVIDENCE_LIMIT = 50;
    private static final int RELATED_WORK_ORDER_LIMIT = 20;

    private final BizStorageOrderMapper orderMapper;
    private final BizCustomerMapper customerMapper;
    private final BizCabinetMapper cabinetMapper;
    private final BizCompartmentMapper slotMapper;
    private final BizSiteMapper siteMapper;
    private final BizDeviceCommandMapper commandMapper;
    private final BizItemEvidenceMapper evidenceMapper;
    private final BizWorkOrderMapper workOrderMapper;
    private final StorageQueryService storageQueries;

    public AdminOrderQueryService(BizStorageOrderMapper orderMapper,
                                  BizCustomerMapper customerMapper,
                                  BizCabinetMapper cabinetMapper,
                                  BizCompartmentMapper slotMapper,
                                  BizSiteMapper siteMapper,
                                  BizDeviceCommandMapper commandMapper,
                                  BizItemEvidenceMapper evidenceMapper,
                                  BizWorkOrderMapper workOrderMapper,
                                  StorageQueryService storageQueries) {
        this.orderMapper = orderMapper;
        this.customerMapper = customerMapper;
        this.cabinetMapper = cabinetMapper;
        this.slotMapper = slotMapper;
        this.siteMapper = siteMapper;
        this.commandMapper = commandMapper;
        this.evidenceMapper = evidenceMapper;
        this.workOrderMapper = workOrderMapper;
        this.storageQueries = storageQueries;
    }

    /** 列表行：客服一眼要能对上“哪张单、在哪个柜子、欠不欠钱”。 */
    public record OrderRow(Long id, String orderNo, String status, String sizeType,
                           Long customerId, String customerName,
                           String siteName, String cabinetNo, String slotNo,
                           long prepayPoints, long settledPoints, long arrearsPoints,
                           LocalDateTime createTime, LocalDateTime startedAt, LocalDateTime finishedAt) {
    }

    /** 一张指令流水：AI/客服判断“门到底开没开”靠的就是这一行。 */
    public record CommandLine(String action, String state, int retryCount,
                              LocalDateTime sentAt, LocalDateTime ackedAt, String lastError) {
    }

    /** 一次物检测量（只增不改的证据）。 */
    public record EvidenceLine(String source, String presence, String moment,
                               BigDecimal confidence, String aiLabel, String note,
                               LocalDateTime at) {
    }

    /** 这张单开过的工单（让客服先看有没有人已经在处理）。 */
    public record WorkOrderLine(Long id, String status, String category, String priority,
                                String closeReason, String title, LocalDateTime createTime) {
    }

    /**
     * 详情：单 + 钱 + 设备动作 + 证据 + 关联工单。
     *
     * <p>{@code customerPhone} 只在详情返回、不加 {@code @JsonMask}：与工单详情同一口径，
     * 客服要能回拨；列表没有这个字段，避免翻页把全租户手机号带出去。
     */
    public record OrderDetail(OrderRow row, String customerPhone,
                              String closeReason, String disputeState, int aiReviewCount,
                              int tempOpenCount, int voucherWrongCount, boolean voucherLocked,
                              boolean leftoverReported, long depositPoints, long remoteClosePoints,
                              StorageQueryService.BillView bill,
                              List<CommandLine> commands, List<EvidenceLine> evidence,
                              List<WorkOrderLine> workOrders) {
    }

    /**
     * 分页查询。所有条件都可选；一个都不传就是“按创建时间倒序看最近的单”。
     *
     * <p>手机号精确匹配、命中不到客户时返回空页而不是 400：
     * “查不到”是正常结果，不是参数错误——报错会让客服以为系统坏了。
     */
    public PageResult<OrderRow> list(String orderNo, String phone, OrderStatus status,
                                     LocalDate from, LocalDate to, PageQuery query) {
        var wrapper = Wrappers.<BizStorageOrder>lambdaQuery();
        if (StringUtils.hasText(orderNo)) {
            wrapper.eq(BizStorageOrder::getOrderNo, orderNo.trim());
        }
        if (StringUtils.hasText(phone)) {
            List<Long> customerIds = customerMapper.selectList(Wrappers.<BizCustomer>lambdaQuery()
                            .select(BizCustomer::getId)
                            .eq(BizCustomer::getPhone, phone.trim()))
                    .stream().map(BizCustomer::getId).toList();
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
        BizCustomer customer = order.getCustomerId() == null
                ? null : customerMapper.selectById(order.getCustomerId());
        BizCabinet cabinet = order.getCabinetId() == null
                ? null : cabinetMapper.selectById(order.getCabinetId());
        BizCompartment slot = order.getSlotId() == null
                ? null : slotMapper.selectById(order.getSlotId());
        BizSite site = cabinet == null || cabinet.getSiteId() == null
                ? null : siteMapper.selectById(cabinet.getSiteId());

        List<CommandLine> commands = commandMapper.selectList(Wrappers.<BizDeviceCommand>lambdaQuery()
                        .eq(BizDeviceCommand::getOrderId, order.getId())
                        .orderByDesc(BizDeviceCommand::getSentAt)
                        .last("limit " + COMMAND_LIMIT))
                .stream()
                .map(c -> new CommandLine(name(c.getAction()), name(c.getStatus()), nvl(c.getRetryCount()),
                        c.getSentAt(), c.getAckedAt(), c.getLastError()))
                .toList();

        List<EvidenceLine> evidence = evidenceMapper.selectList(Wrappers.<BizItemEvidence>lambdaQuery()
                        .eq(BizItemEvidence::getOrderId, order.getId())
                        .orderByAsc(BizItemEvidence::getCreateTime)
                        .last("limit " + EVIDENCE_LIMIT))
                .stream()
                .map(e -> new EvidenceLine(name(e.getSource()), name(e.getPresence()), name(e.getMoment()),
                        e.getConfidence(), e.getAiLabel(), e.getNote(), e.getCreateTime()))
                .toList();

        List<WorkOrderLine> workOrders = workOrderMapper.selectList(Wrappers.<BizWorkOrder>lambdaQuery()
                        .eq(BizWorkOrder::getStorageId, order.getId())
                        .orderByDesc(BizWorkOrder::getCreateTime)
                        .last("limit " + RELATED_WORK_ORDER_LIMIT))
                .stream()
                .map(w -> new WorkOrderLine(w.getId(), name(w.getStatus()), name(w.getCategory()),
                        name(w.getPriority()), name(w.getCloseReason()), w.getTitle(), w.getCreateTime()))
                .toList();

        return new OrderDetail(toRow(order, customer, cabinet, slot, site),
                customer == null ? null : customer.getPhone(),
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

        Map<Long, BizCustomer> customers = customerIds.isEmpty() ? Map.of()
                : customerMapper.selectList(Wrappers.<BizCustomer>lambdaQuery()
                        .in(BizCustomer::getId, customerIds))
                .stream().collect(Collectors.toMap(BizCustomer::getId, c -> c));
        Map<Long, BizCabinet> cabinets = cabinetIds.isEmpty() ? Map.of()
                : cabinetMapper.selectList(Wrappers.<BizCabinet>lambdaQuery()
                        .in(BizCabinet::getId, cabinetIds))
                .stream().collect(Collectors.toMap(BizCabinet::getId, c -> c));
        Map<Long, BizCompartment> slots = slotIds.isEmpty() ? Map.of()
                : slotMapper.selectList(Wrappers.<BizCompartment>lambdaQuery()
                        .in(BizCompartment::getId, slotIds))
                .stream().collect(Collectors.toMap(BizCompartment::getId, s -> s));
        Set<Long> siteIds = cabinets.values().stream().map(BizCabinet::getSiteId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, BizSite> sites = siteIds.isEmpty() ? Map.of()
                : siteMapper.selectList(Wrappers.<BizSite>lambdaQuery()
                        .in(BizSite::getId, siteIds))
                .stream().collect(Collectors.toMap(BizSite::getId, s -> s));

        return orders.stream()
                .map(o -> {
                    BizCabinet cabinet = cabinets.get(o.getCabinetId());
                    BizSite site = cabinet == null || cabinet.getSiteId() == null
                            ? null : sites.get(cabinet.getSiteId());
                    return toRow(o, customers.get(o.getCustomerId()), cabinet,
                            slots.get(o.getSlotId()), site);
                })
                .toList();
    }

    private static OrderRow toRow(BizStorageOrder order, BizCustomer customer, BizCabinet cabinet,
                                  BizCompartment slot, BizSite site) {
        return new OrderRow(order.getId(), order.getOrderNo(),
                name(order.getStatus()), name(order.getSizeType()),
                order.getCustomerId(), customer == null ? null : customer.getNickname(),
                site == null ? null : site.getName(),
                cabinet == null ? null : cabinet.getCabinetNo(),
                slot == null ? null : slot.getSlotNo(),
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
