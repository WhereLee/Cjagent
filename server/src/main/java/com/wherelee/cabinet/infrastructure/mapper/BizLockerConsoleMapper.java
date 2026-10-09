package com.wherelee.cabinet.infrastructure.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.wherelee.cabinet.domain.entity.BizCompartment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 运营后台的**读模型**（台账与清单）。
 *
 * <p>为什么不塞进各业务的单表 Mapper：台账查询天生是跨表聚合（格口 + 柜机 + 点位 + 订单 + 押金），
 * 还带"停留多久""谁欠多少"这种派生列。放进单表 Mapper 会让人误以为它是普通 CRUD，
 * 进而有人往里面加写方法——读模型只读的边界要靠文件边界来守。
 *
 * <p>三条口径写在 SQL 里而不是在 Java 里补：
 * <ul>
 *   <li><b>租户条件手写</b>：这几条都是 join/聚合，多租户拦截器对 join 的注入结果不易核对，
 *       写错就是跨租户看数据（与对账作业同一取向）；</li>
 *   <li><b>异常台账按 <code>abnormal_at</code> 算停留时长</b>：运营排片的依据是"卡了多久"，
 *       不是"什么时候建的"；</li>
 *   <li><b>欠费只统计已终态的单</b>：进行中的单 <code>arrears_points</code> 恒为 0，
 *     把它们算进去不会错，但会让聚合白扫一遍活动单。</li>
 * </ul>
 */
@Mapper
public interface BizLockerConsoleMapper {

    /** 台账行：格口 + 异常原因 + 停留分钟 + 柜机/点位与当前占用人。 */
    @Select("""
            select c.id, c.tenant_id, c.cabinet_id, c.slot_no, c.size_type, c.status, c.current_order_id,
                   c.door_open_at, c.door_closed_at, c.presence, c.presence_checked_at,
                   c.anomaly, c.anomaly_at, c.anomaly_reason, c.version,
                   c.create_time, c.update_time, c.deleted,
                   cab.cabinet_no                                  as cabinetNo,
                   cab.site_id                                     as siteId,
                   s.name                                          as siteName,
                   timestampdiff(MINUTE, c.anomaly_at, now(3))    as stuckMinutes
              from biz_compartment c
              join biz_cabinet cab on cab.id = c.cabinet_id and cab.deleted = 0
              join biz_site s      on s.id  = cab.site_id   and s.deleted = 0
             where c.deleted = 0
               and c.anomaly is not null
               and (#{anomaly} is null or c.anomaly = #{anomaly})
               and (#{siteId} is null or cab.site_id = #{siteId})
             order by c.anomaly_at
            """)
    IPage<LedgerRow> pageAnomalies(IPage<LedgerRow> page,
                                   @Param("anomaly") String anomaly,
                                   @Param("siteId") Long siteId);

    /** 台账按原因码计数：后台顶部的"哪类积压多少"，让排片有依据而不是逐条翻页。 */
    @Select("""
            select c.anomaly as anomaly, count(*) as rowsCount,
                   coalesce(max(timestampdiff(MINUTE, c.anomaly_at, now(3))), 0) as longestMinutes
              from biz_compartment c
              join biz_cabinet cab on cab.id = c.cabinet_id and cab.deleted = 0
             where c.deleted = 0 and c.anomaly is not null
               and (#{siteId} is null or cab.site_id = #{siteId})
             group by c.anomaly
            """)
    List<AnomalySummary> summarizeAnomalies(@Param("siteId") Long siteId);

    /** 未退押金清单：凭证仍是 HELD/REFUNDING/FAILED，且订单已进终态（在线单挂着押金是正常态）。 */
    @Select("""
            select d.id as depositId, d.order_id as orderId, d.customer_id as customerId,
                   d.points as points, d.status as depositStatus, o.order_no as orderNo,
                   o.status as orderStatus, timestampdiff(HOUR, d.held_at, now(3)) as heldHours
              from biz_deposit d
              join biz_storage_order o on o.id = d.order_id and o.deleted = 0
             where d.deleted = 0
               and d.status in ('HELD', 'REFUNDING', 'FAILED')
               and o.status in ('CLOSED', 'CANCELLED')
             order by d.held_at
            """)
    IPage<UnrefundedDeposit> pageUnrefundedDeposits(IPage<UnrefundedDeposit> page);

    /** 欠费客户清单：按客户汇总未缴金额，追缴与禁用判断都看这张表。 */
    @Select("""
            select o.customer_id as customerId, sum(o.arrears_points) as arrearsPoints,
                   count(*) as arrearsOrders, max(o.finished_at) as lastFinishedAt
              from biz_storage_order o
             where o.deleted = 0 and o.arrears_points > 0
             group by o.customer_id
             order by sum(o.arrears_points) desc
            """)
    IPage<ArrearsRow> pageArrears(IPage<ArrearsRow> page);

    /**
     * 某个客户的欠费合计。<b>下单拦截靠它</b>：规则是"欠费 &gt; 0 就不得再下单"，没有阈值。
     *
     * <p>coalesce 包在聚合上而不是包在列上：把 {@code coalesce(arrears_points,0)} 写进 where
     * 会让索引失效（架构约定 §16.4 的那条），这里只对外层结果取 0。
     */
    @Select("""
            select coalesce(sum(o.arrears_points), 0)
              from biz_storage_order o
             where o.deleted = 0 and o.customer_id = #{customerId} and o.arrears_points > 0
            """)
    long sumCustomerArrears(@Param("customerId") Long customerId);

    /** 台账行（继承格口实体字段，附柜机/点位与停留时长）。 */
    class LedgerRow extends BizCompartment {
        private String cabinetNo;
        private Long siteId;
        private String siteName;
        private Long stuckMinutes;

        public String getCabinetNo() { return cabinetNo; }
        public void setCabinetNo(String cabinetNo) { this.cabinetNo = cabinetNo; }
        public Long getSiteId() { return siteId; }
        public void setSiteId(Long siteId) { this.siteId = siteId; }
        public String getSiteName() { return siteName; }
        public void setSiteName(String siteName) { this.siteName = siteName; }
        public Long getStuckMinutes() { return stuckMinutes; }
        public void setStuckMinutes(Long stuckMinutes) { this.stuckMinutes = stuckMinutes; }
    }

    class AnomalySummary {
        private String anomaly;
        private long rowsCount;
        private long longestMinutes;

        public String getAnomaly() { return anomaly; }
        public void setAnomaly(String anomaly) { this.anomaly = anomaly; }
        public long getRowsCount() { return rowsCount; }
        public void setRowsCount(long rowsCount) { this.rowsCount = rowsCount; }
        public long getLongestMinutes() { return longestMinutes; }
        public void setLongestMinutes(long longestMinutes) { this.longestMinutes = longestMinutes; }
    }

    class UnrefundedDeposit {
        private Long depositId;
        private Long orderId;
        private Long customerId;
        private Long points;
        private String depositStatus;
        private String orderNo;
        private String orderStatus;
        private Long heldHours;

        public Long getDepositId() { return depositId; }
        public void setDepositId(Long depositId) { this.depositId = depositId; }
        public Long getOrderId() { return orderId; }
        public void setOrderId(Long orderId) { this.orderId = orderId; }
        public Long getCustomerId() { return customerId; }
        public void setCustomerId(Long customerId) { this.customerId = customerId; }
        public Long getPoints() { return points; }
        public void setPoints(Long points) { this.points = points; }
        public String getDepositStatus() { return depositStatus; }
        public void setDepositStatus(String depositStatus) { this.depositStatus = depositStatus; }
        public String getOrderNo() { return orderNo; }
        public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
        public String getOrderStatus() { return orderStatus; }
        public void setOrderStatus(String orderStatus) { this.orderStatus = orderStatus; }
        public Long getHeldHours() { return heldHours; }
        public void setHeldHours(Long heldHours) { this.heldHours = heldHours; }
    }

    class ArrearsRow {
        private Long customerId;
        private Long arrearsPoints;
        private int arrearsOrders;
        private java.time.LocalDateTime lastFinishedAt;

        public Long getCustomerId() { return customerId; }
        public void setCustomerId(Long customerId) { this.customerId = customerId; }
        public Long getArrearsPoints() { return arrearsPoints; }
        public void setArrearsPoints(Long arrearsPoints) { this.arrearsPoints = arrearsPoints; }
        public int getArrearsOrders() { return arrearsOrders; }
        public void setArrearsOrders(int arrearsOrders) { this.arrearsOrders = arrearsOrders; }
        public java.time.LocalDateTime getLastFinishedAt() { return lastFinishedAt; }
        public void setLastFinishedAt(java.time.LocalDateTime lastFinishedAt) { this.lastFinishedAt = lastFinishedAt; }
    }
}
