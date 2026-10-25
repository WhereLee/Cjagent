package com.wherelee.cabinet.storage.api;

import com.wherelee.cabinet.domain.entity.BizStorageOrder;

import java.time.LocalDateTime;
import java.util.List;

public interface StorageQueryPort {

    record SiteView(String siteCode, String name, String category, String address, long cabinetCount,
                    long freeSmall, long freeMedium, long freeLarge) {
    }

    record CabinetView(String cabinetNo, String name, String status, String onlineState) {
    }

    record OrderView(String orderNo, String cabinetNo, String slotNo, String sizeType, String status,
                     LocalDateTime startedAt, LocalDateTime expectedFinishAt, LocalDateTime createTime,
                     long settledPoints, long arrearsPoints,
                     boolean leftoverRetrievable) {
    }

    record WalletView(long points, long frozenPoints, long depositPoints, long arrearsPoints,
                      long unpaidOrders, long activeOrders) {
    }

    record BillLine(String type, long amount, long balanceAfter, String remark, LocalDateTime at) {
    }

    record BillView(String orderNo, String status, String sizeType,
                    LocalDateTime startedAt, LocalDateTime finishedAt, long billedMinutes,
                    long unitPointsPerHour, int freeMinutes, int dailyCapHours, int capDays,
                    int billedHours, long chargePoints,
                    long prepayPoints, long settledPoints, long arrearsPoints,
                    List<BillLine> lines) {
    }

    VoucherView voucher(Long customerId, String orderNo);

    List<SiteView> sites();

    List<CabinetView> cabinets(String siteCode);

    List<OrderView> myOrders(Long customerId, int limit);

    WalletView wallet(Long customerId);

    BillView bill(Long customerId, String orderNo);

    BillView billOf(BizStorageOrder order);
}
