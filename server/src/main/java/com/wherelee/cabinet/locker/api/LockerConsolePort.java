package com.wherelee.cabinet.locker.api;

import com.wherelee.cabinet.common.api.PageQuery;
import com.wherelee.cabinet.common.api.PageResult;
import com.wherelee.cabinet.device.api.EvidenceSnapshot;
import com.wherelee.cabinet.locker.domain.BizCompartment;
import com.wherelee.cabinet.storage.api.OrderSnapshot;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 后台格口台账入口：异常台账、积压概览、格口详情、未退押金、欠费清单与两条处置动作。
 *
 * <p>为什么放在 api：BFF 之前直接注入 {@code LockerConsoleService}，等于把“台账怎么查”
 * 绑进了对外接口层。这里保留同名视图记录（字段一个不改），所以 HTTP 契约不变；
 * 变的只是调用方依赖的类型。
 *
 * <p>{@link Detail} 里带实体是<b>现状的契约</b>（详情页今天就把整行格口给前端），
 * 不是鼓励这么设计；要收窄字段应当作为一次显式的契约变更来做，而不是藏在重构里。
 */
public interface LockerConsolePort {

    /** 格口详情出参。 */
    record Detail(BizCompartment compartment, List<EvidenceSnapshot> evidence, OrderSnapshot currentOrder) {
    }

    /**
     * 台账行视图。<b>接口层不得直接用 mapper 的行对象</b>：
     * 行对象跟着 SQL 列变，一变就把 SQL 细节透给了前端契约。
     */
    record LedgerView(Long compartmentId, Long cabinetId, String cabinetNo, String slotNo, String anomaly,
                      LocalDateTime anomalyAt, Long stuckMinutes, String anomalyReason,
                      Long siteId, String siteName, Long currentOrderId) {
    }

    /** 积压概览行。 */
    record SummaryView(String anomaly, long rowsCount, long longestMinutes) {
    }

    /** 未退押金行。 */
    record DepositView(Long depositId, String orderNo, Long customerId, Long points,
                       String depositStatus, Long heldHours) {
    }

    /** 欠费客户行。 */
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
