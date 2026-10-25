package com.wherelee.cabinet.funds.infra;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.wherelee.cabinet.funds.domain.BizPointAccount;
import lombok.Getter;
import lombok.Setter;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

@Mapper
public interface BizReconcileMapper {

    @Getter
    @Setter
    class BucketSum {

        private Long customerId;
        private Long sumPoints;
        private Long sumFrozen;
    }

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            select id, tenant_id, customer_id, points, frozen_points, version,
                   create_time, update_time, deleted
              from biz_point_account
             where deleted = 0 and tenant_id = #{tenantId} and id > #{afterId}
             order by id
             limit #{batchSize}
            """)
    List<BizPointAccount> nextAccountBatch(@Param("tenantId") Long tenantId,
                                           @Param("afterId") Long afterId,
                                           @Param("batchSize") int batchSize);

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            <script>
            select customer_id,
                   coalesce(sum(case when biz_type in
                       <foreach item="t" collection="pointsTypes" open="(" separator="," close=")">#{t}</foreach>
                       then amount else 0 end), 0)                           as sum_points,
                   coalesce(sum(case when biz_type in
                       <foreach item="t" collection="frozenTypes" open="(" separator="," close=")">#{t}</foreach>
                       then amount else 0 end), 0)                           as sum_frozen
              from biz_point_txn
             where customer_id in
               <foreach item="c" collection="customerIds" open="(" separator="," close=")">#{c}</foreach>
             group by customer_id
            </script>
            """)
    List<BucketSum> bucketSums(@Param("customerIds") Collection<Long> customerIds,
                               @Param("pointsTypes") List<String> pointsTypes,
                               @Param("frozenTypes") List<String> frozenTypes);

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            select count(*)
              from biz_deposit d
              join biz_storage_order o on o.id = d.order_id
             where d.deleted = 0 and o.deleted = 0
               and d.tenant_id = #{tenantId}
               and d.status in ('HELD', 'REFUNDING', 'FAILED')
               and o.status in ('CLOSED', 'CANCELLED')
            """)
    long hangingDeposits(@Param("tenantId") Long tenantId);

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            select o.order_no
              from biz_deposit d
              join biz_storage_order o on o.id = d.order_id
             where d.deleted = 0 and o.deleted = 0
               and d.tenant_id = #{tenantId}
               and d.status in ('HELD', 'REFUNDING', 'FAILED')
               and o.status in ('CLOSED', 'CANCELLED')
             order by d.held_at
             limit #{limit}
            """)
    List<String> findHangingDepositOrderNos(@Param("tenantId") Long tenantId, @Param("limit") int limit);

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            select count(*)
              from biz_storage_order o
              left join biz_compartment c on c.id = o.slot_id and c.deleted = 0
             where o.deleted = 0
               and o.tenant_id = #{tenantId}
               and o.active_flag is not null
               and (c.id is null or c.status = 'FREE')
            """)
    long orphanActiveOrders(@Param("tenantId") Long tenantId);

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            select count(*)
              from biz_storage_order
             where deleted = 0 and tenant_id = #{tenantId} and status = 'EXPIRED'
            """)
    long strandedOrders(@Param("tenantId") Long tenantId);

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            select count(*)
              from biz_compartment c
              left join biz_storage_order o
                     on o.id = c.current_order_id and o.deleted = 0 and o.active_flag is not null
             where c.deleted = 0
               and c.tenant_id = #{tenantId}
               and c.status in ('RESERVED', 'OCCUPIED')
               and o.id is null
            """)
    long lockedSlotsWithoutOrder(@Param("tenantId") Long tenantId);

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            select count(*)
              from biz_pay_txn p
              left join biz_point_txn t
                     on t.biz_no = p.out_trade_no and t.biz_type = 'RECHARGE'
            where p.deleted = 0
              and p.tenant_id = #{tenantId}
              and p.status = 'PAID'
              and t.id is null
            """)
    long paidButNotCredited(@Param("tenantId") Long tenantId);

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            select count(*)
              from biz_point_txn t
              left join biz_pay_txn p
                     on p.out_trade_no = t.biz_no and p.status = 'PAID' and p.deleted = 0
            where t.biz_type = 'RECHARGE'
              and t.biz_no like 'RC%'
              and t.tenant_id = #{tenantId}
              and p.id is null
            """)
    long creditedWithoutPay(@Param("tenantId") Long tenantId);
}
