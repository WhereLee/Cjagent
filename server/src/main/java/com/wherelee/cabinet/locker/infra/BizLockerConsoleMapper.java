package com.wherelee.cabinet.locker.infra;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.wherelee.cabinet.locker.domain.BizCompartment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface BizLockerConsoleMapper {

    @Select("""
            select c.id, c.tenant_id, c.cabinet_id, c.slot_no, c.size_type, c.status, c.current_order_id,
                   c.door_open_at, c.door_closed_at, c.presence, c.presence_checked_at,
                   c.anomaly, c.anomaly_at, c.anomaly_reason, c.version,
                   c.create_time, c.update_time, c.deleted,
                   cab.cabinet_no                                  as cabinet_no,
                   cab.id                                          as cabinet_id,
                   cab.site_id                                     as site_id,
                   s.name                                          as site_name,
                   timestampdiff(MINUTE, c.anomaly_at, now(3))    as stuck_minutes
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

    @Select("""
            select c.anomaly as anomaly, count(*) as rows_count,
                   coalesce(max(timestampdiff(MINUTE, c.anomaly_at, now(3))), 0) as longest_minutes
              from biz_compartment c
              join biz_cabinet cab on cab.id = c.cabinet_id and cab.deleted = 0
             where c.deleted = 0 and c.anomaly is not null
               and (#{siteId} is null or cab.site_id = #{siteId})
             group by c.anomaly
            """)
    List<AnomalySummary> summarizeAnomalies(@Param("siteId") Long siteId);

    @Select("""
            select d.id as deposit_id, d.order_id as order_id, d.customer_id as customer_id,
                   d.points as points, d.status as deposit_status, o.order_no as order_no,
                   o.status as order_status, timestampdiff(HOUR, d.held_at, now(3)) as held_hours
              from biz_deposit d
              join biz_storage_order o on o.id = d.order_id and o.deleted = 0
             where d.deleted = 0
               and d.status in ('HELD', 'REFUNDING', 'FAILED')
               and o.status in ('CLOSED', 'CANCELLED')
             order by d.held_at
            """)
    IPage<UnrefundedDeposit> pageUnrefundedDeposits(IPage<UnrefundedDeposit> page);

    @Select("""
            select o.customer_id as customer_id, sum(o.arrears_points) as arrears_points,
                   count(*) as arrears_orders, max(o.finished_at) as last_finished_at
              from biz_storage_order o
             where o.deleted = 0 and o.arrears_points > 0
             group by o.customer_id
             order by sum(o.arrears_points) desc
            """)
    IPage<ArrearsRow> pageArrears(IPage<ArrearsRow> page);

    @Select("""
            select coalesce(sum(o.arrears_points), 0)
              from biz_storage_order o
             where o.deleted = 0 and o.customer_id = #{customerId} and o.arrears_points > 0
            """)
    long sumCustomerArrears(@Param("customerId") Long customerId);

    class LedgerRow extends BizCompartment {
        private String cabinetNo;
        private Long cabinetId;
        private Long siteId;
        private String siteName;
        private Long stuckMinutes;

        public String getCabinetNo() { return cabinetNo; }
        public void setCabinetNo(String cabinetNo) { this.cabinetNo = cabinetNo; }
        public Long getCabinetId() { return cabinetId; }
        public void setCabinetId(Long cabinetId) { this.cabinetId = cabinetId; }
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
