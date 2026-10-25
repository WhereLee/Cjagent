package com.wherelee.cabinet.locker.api;

import com.wherelee.cabinet.common.api.PageQuery;
import com.wherelee.cabinet.common.api.PageResult;
import com.wherelee.cabinet.device.api.EvidenceSnapshot;
import com.wherelee.cabinet.locker.domain.BizCompartment;
import com.wherelee.cabinet.storage.api.OrderSnapshot;

import java.time.LocalDateTime;
import java.util.List;

public interface LockerConsolePort {

    record Detail(BizCompartment compartment, List<EvidenceSnapshot> evidence, OrderSnapshot currentOrder) {
    }

    record LedgerView(Long compartmentId, Long cabinetId, String cabinetNo, String slotNo, String anomaly,
                      LocalDateTime anomalyAt, Long stuckMinutes, String anomalyReason,
                      Long siteId, String siteName, Long currentOrderId) {
    }

    record SummaryView(String anomaly, long rowsCount, long longestMinutes) {
    }

    record DepositView(Long depositId, String orderNo, Long customerId, Long points,
                       String depositStatus, Long heldHours) {
    }

    record ArrearsView(Long customerId, Long arrearsPoints, int arrearsOrders,
                       LocalDateTime lastFinishedAt) {
    }

    PageResult<LedgerView> anomalies(PageQuery query, String anomaly, Long siteId);

    List<SummaryView> anomalySummary(Long siteId);

    PageResult<DepositView> unrefundedDeposits(PageQuery query);

    PageResult<ArrearsView> arrears(PageQuery query);

    Detail compartmentDetail(Long compartmentId);

    void resolveAnomaly(Long compartmentId, String note);

    void forceOpen(Long compartmentId, boolean confirmed, String reason, Long adminId);
}
