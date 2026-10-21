package com.wherelee.cabinet.storage.api;

import com.wherelee.cabinet.domain.entity.BizStorageOrder;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户端寄存查询入口：点位/柜机、我的订单、钱包、账单、取件码。
 *
 * <p>视图记录原名搬入，OpenAPI schema 名与字段不变；账单与后台详情共用同一份
 * {@link BillView}，避免两处各算一遍导致金额口径不一致。
 */
public interface StorageQueryPort {

    /** 点位：找地方需要的字段 + 各尺寸还剩多少正常格口（全异常就是 0）。 */
    record SiteView(String siteCode, String name, String category, String address, long cabinetCount,
                    long freeSmall, long freeMedium, long freeLarge) {
    }

    /** 点位下的一台暂存柜。状态与在线情况原样读出来给人看，不在这里下判断。 */
    record CabinetView(String cabinetNo, String name, String status, String onlineState) {
    }

    /**
     * 一张寄存单的可见信息（含柜机与格口编号，用户凭这两个字段才能在柜机前对上位置）。
     * leftoverRetrievable：这张单的格口正有他的遗留物待取回（与开门校验同一判据）。
     */
    record OrderView(String orderNo, String cabinetNo, String slotNo, String sizeType, String status,
                     LocalDateTime startedAt, LocalDateTime expectedFinishAt, LocalDateTime createTime,
                     long settledPoints, long arrearsPoints,
                     boolean leftoverRetrievable) {
    }

    /** 钱包：余额、冻结、押金栏（历史遗留栏，仍要如实读出来）、欠费合计与未支付笔数。 */
    record WalletView(long points, long frozenPoints, long depositPoints, long arrearsPoints,
                      long unpaidOrders, long activeOrders) {
    }

    /** 一笔资金流水（用户能逐笔核对“钱去哪了”）。 */
    record BillLine(String type, long amount, long balanceAfter, String remark, LocalDateTime at) {
    }

    /** 账单明细视图。金额全部是点（1 点 = 1 分），不在此处换算成元。 */
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

    /** 按单构账单（不校验归属）：后台详情与用户端账单共用这一份口径。 */
    BillView billOf(BizStorageOrder order);
}
