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

/**
 * 寄存单 Mapper。
 *
 * <p>争议相关的儿条更新都写成**带条件的单列更新**而不是 `updateById`，两个理由：
 * ① 争议字段与订单主态是两个关心的事，走 `updateById` 会连着 `version` 一起碰，
 * 使“外层的结束事务回滚”与“争议记录要留下”这两件事无法共存（见 ItemDisputeService 的 REQUIRES_NEW）；
 * ② “只记第一次”、“不能把时间往后推”这类语义只能写在 SQL 里才真的原子。
 */
@Mapper
public interface BizStorageOrderMapper extends BaseMapper<BizStorageOrder> {

    /**
     * 某客户全部欠费合计（下单拦截与钱包展示共用这一个口径）。
     *
     * <p>coalesce 包在聚合上而不是包在列上：把 {@code coalesce(arrears_points,0)} 写进 where
     * 会让索引失效，这里只对外层结果取 0。
     */
    @Select("""
            select coalesce(sum(o.arrears_points), 0)
              from biz_storage_order o
             where o.deleted = 0 and o.customer_id = #{customerId} and o.arrears_points > 0
            """)
    long sumCustomerArrears(@Param("customerId") Long customerId);

    /**
     * 按取件码找一张还活着的单（定-25 的双因子入口专用）。
     *
     * <p>{@code @InterceptorIgnore(tenantLine)}：这个端点匿名可达，请求里本来就没有租户，
     * 带租户条件的 SQL 会被守卫拒掉（与归档、调度取任务同一套做法）。码是全表唯一的（uk_order_voucher），
     * 所以跳租户不会造成歧义；拿到 tenant_id 后上层立即切回租户上下文。
     *
     * <p>只取定位需要的几列（不取金额与快照）：拿到的只是一个“去哪儿读完整单”的坐标，
     * 真正的结算必须重读完整行。
     */
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

    /**
     * 记下争议起始时刻：只有仍为 NULL 时才写。
     *
     * <p>这不是为了省一次更新，而是为了**让反复点“我没落东西”无法把计费终点往后推**：
     * 一旦可以刷成最新时刻，“误报免除”反而变成加费。
     */
    @Update("update biz_storage_order set dispute_state = #{state}, dispute_started_at = #{at}, "
            + "update_time = NOW(3) where id = #{id} and dispute_started_at is null and deleted = 0")
    int markDisputeStart(@Param("id") Long id, @Param("state") DisputeState state,
                         @Param("at") LocalDateTime at);

    /** 推进复审进度（状态 + 次数）。次数由调用方给，不在 SQL 里 +1：上限判定需要拿到“这是第几次”。 */
    @Update("update biz_storage_order set dispute_state = #{state}, ai_review_count = #{reviews}, "
            + "update_time = NOW(3) where id = #{id} and deleted = 0")
    int markReview(@Param("id") Long id, @Param("state") DisputeState state,
                   @Param("reviews") int reviews);

    /** 给原主单打“遗留物被他人发现”标记：只记第一次（同一单不必刷时间）。 */
    @Update("update biz_storage_order set leftover_reported_at = #{at}, update_time = NOW(3) "
            + "where id = #{id} and leftover_reported_at is null and deleted = 0")
    int markLeftoverReported(@Param("id") Long id, @Param("at") LocalDateTime at);

    /**
     * 某客户待清偿的欠费单（按结算时间先后，**先欠的先还**）。
     *
     * <p>不设条数上限：一个客户的欠费单数天然有限，而“只还前 N 条”会制造
     * “欠费台账有 40 条、系统只认 20 条”这种两边对不上的口径。
     */
    @Select("select * from biz_storage_order where deleted = 0 and customer_id = #{customerId} "
            + "and arrears_points > 0 order by finished_at, id")
    List<BizStorageOrder> listOwed(@Param("customerId") Long customerId);

    /**
     * 从一张单上减掉已补缴的欠额。<b>条件更新</b>：`arrears_points >= #{amount}` 把“超还”
     * 在存储层就挡住——并发下两条路径同时还同一张单时，只可能有一条成立。
     */
    @Update("update biz_storage_order set arrears_points = arrears_points - #{amount}, update_time = NOW(3) "
            + "where id = #{id} and deleted = 0 and arrears_points >= #{amount} and arrears_points > 0")
    int reduceArrears(@Param("id") Long id, @Param("amount") long amount);
}
