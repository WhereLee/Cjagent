package com.wherelee.cabinet.storage.api;

import com.wherelee.cabinet.common.api.PageQuery;
import com.wherelee.cabinet.common.api.PageResult;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 后台订单查询入口：列表与详情（含指令、证据、关联工单、账单）。
 *
 * <p>视图记录的 simple name 与字段保持原样，OpenAPI schema 名不变；
 * 变的只是调用方依赖的包。详情里的 {@code bill} 复用用户端同一个账单视图，
 * 保证“后台说欠 3 块、用户看到欠 5 块”这种事不会发生。
 */
public interface AdminOrderConsolePort {

    /** 列表行：客服一眼要能对上“哪张单、在哪个柜子、欠不欠钱”。 */
    record OrderRow(Long id, String orderNo, String status, String sizeType,
                    Long customerId, String customerName,
                    String siteName, String cabinetNo, String slotNo,
                    long prepayPoints, long settledPoints, long arrearsPoints,
                    LocalDateTime createTime, LocalDateTime startedAt, LocalDateTime finishedAt) {
    }

    /** 一张指令流水：AI/客服判断“门到底开没开”靠的就是这一行。 */
    record CommandLine(String action, String state, int retryCount,
                       LocalDateTime sentAt, LocalDateTime ackedAt, String lastError) {
    }

    /** 一次物检测量（只增不改的证据）。 */
    record EvidenceLine(String source, String presence, String moment,
                        BigDecimal confidence, String aiLabel, String note,
                        LocalDateTime at) {
    }

    /** 这张单开过的工单（让客服先看有没有人已经在处理）。 */
    record WorkOrderLine(Long id, String status, String category, String priority,
                         String closeReason, String title, LocalDateTime createTime) {
    }

    /**
     * 详情：单 + 钱 + 设备动作 + 证据 + 关联工单。
     *
     * <p>{@code customerPhone} 只在详情返回：客服要能回拨；列表没有这个字段，
     * 避免翻页把全租户手机号带出去。
     */
    record OrderDetail(OrderRow row, String customerPhone,
                       String closeReason, String disputeState, int aiReviewCount,
                       int tempOpenCount, int voucherWrongCount, boolean voucherLocked,
                       boolean leftoverReported, long depositPoints, long remoteClosePoints,
                       StorageQueryPort.BillView bill,
                       List<CommandLine> commands, List<EvidenceLine> evidence,
                       List<WorkOrderLine> workOrders) {
    }

    PageResult<OrderRow> list(String orderNo, String phone, OrderStatus status,
                              LocalDate from, LocalDate to, PageQuery query);

    OrderDetail detail(String orderNo);
}
