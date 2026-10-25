package com.wherelee.cabinet.storage.api;

import com.wherelee.cabinet.common.api.PageQuery;
import com.wherelee.cabinet.common.api.PageResult;
import com.wherelee.cabinet.domain.enums.OrderStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface AdminOrderConsolePort {

    record OrderRow(Long id, String orderNo, String status, String sizeType,
                    Long customerId, String customerName,
                    String siteName, String cabinetNo, String slotNo,
                    long prepayPoints, long settledPoints, long arrearsPoints,
                    LocalDateTime createTime, LocalDateTime startedAt, LocalDateTime finishedAt) {
    }

    record CommandLine(String action, String state, int retryCount,
                       LocalDateTime sentAt, LocalDateTime ackedAt, String lastError) {
    }

    record EvidenceLine(String source, String presence, String moment,
                        BigDecimal confidence, String aiLabel, String note,
                        LocalDateTime at) {
    }

    record WorkOrderLine(Long id, String status, String category, String priority,
                         String closeReason, String title, LocalDateTime createTime) {
    }

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
