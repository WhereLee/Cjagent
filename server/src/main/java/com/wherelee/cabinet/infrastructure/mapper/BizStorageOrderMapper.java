package com.wherelee.cabinet.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.DisputeState;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface BizStorageOrderMapper extends BaseMapper<BizStorageOrder> {

    @Select("""
            select coalesce(sum(o.arrears_points), 0)
              from biz_storage_order o
             where o.deleted = 0 and o.customer_id = #{customerId} and o.arrears_points > 0
            """)
    long sumCustomerArrears(@Param("customerId") Long customerId);

    @com.baomidou.mybatisplus.annotation.InterceptorIgnore(tenantLine = "true")
    @org.apache.ibatis.annotations.Select("""
            select id, tenant_id, order_no, customer_id, status, voucher_code,
                   voucher_wrong_count, voucher_locked_at
              from biz_storage_order
             where voucher_code = #{voucherCode} and deleted = 0
               and status in ('RESERVED', 'OPENING', 'ACTIVE', 'TEMP_OPEN')
             limit 1
            """)
    BizStorageOrder pickAliveByVoucher(@org.apache.ibatis.annotations.Param("voucherCode") String voucherCode);

    @Update("update biz_storage_order set dispute_state = #{state}, dispute_started_at = #{at}, "
            + "update_time = NOW(3) where id = #{id} and dispute_started_at is null and deleted = 0")
    int markDisputeStart(@Param("id") Long id, @Param("state") DisputeState state,
                         @Param("at") LocalDateTime at);

    @Update("update biz_storage_order set dispute_state = #{state}, ai_review_count = #{reviews}, "
            + "update_time = NOW(3) where id = #{id} and deleted = 0")
    int markReview(@Param("id") Long id, @Param("state") DisputeState state,
                   @Param("reviews") int reviews);

    @Update("update biz_storage_order set leftover_reported_at = #{at}, update_time = NOW(3) "
            + "where id = #{id} and leftover_reported_at is null and deleted = 0")
    int markLeftoverReported(@Param("id") Long id, @Param("at") LocalDateTime at);

    @Select("select * from biz_storage_order where deleted = 0 and customer_id = #{customerId} "
            + "and arrears_points > 0 order by finished_at, id")
    List<BizStorageOrder> listOwed(@Param("customerId") Long customerId);

    @Update("update biz_storage_order set arrears_points = arrears_points - #{amount}, update_time = NOW(3) "
            + "where id = #{id} and deleted = 0 and arrears_points >= #{amount} and arrears_points > 0")
    int reduceArrears(@Param("id") Long id, @Param("amount") long amount);
}
