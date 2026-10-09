package com.wherelee.cabinet.infrastructure.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.wherelee.cabinet.domain.entity.BizPointAccount;
import lombok.Getter;
import lombok.Setter;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 对账批次的只读查询（报表聚合，不参与任何写入）。
 *
 * <p><b>为什么单独一个 Mapper 而不是往业务 Mapper 上加方法</b>：对账的 SQL 形态和 CRUD 完全不同
 * （跨表聚合 + 游标分批），混在一起会让"这个表怎么用"失去单一答案。它也符合 {@code 架构约定.md} §16.7
 * 允许 join 的两类之一：后台只读聚合，且必须自带 EXPLAIN 依据（见 ReconcileBatchTest 的索引断言）。
 *
 * <p><b>全部 {@code @InterceptorIgnore(tenantLine)}，租户条件由 SQL 自己写死。</b>
 * 多表 join 上拦截器会把条件加在哪一侧是不确定的（加错侧就是越租或漏租），
 * 所以这里显式 {@code tenant_id = #{tenantId}}：宁可写一遍重复条件，也不要一个"看起来对"的猜测。
 *
 * <p><b>对账是分批的，不是一条大 SQL 扫全库</b>：账户 10 万、流水千万行时，
 * "一条 group by 出所有差异"会让对账作业自己变成线上事故。形态改成
 * 按 {@code id} 游标取一批账户 → 只对这一批做一次聚合 → 比差异 → 翻页。
 * 每批的代价由 batchSize 决定，和总数据量无关。
 */
@Mapper
public interface BizReconcileMapper {

    /**
     * 两栏流水之和的投影（只装这条聚合读出来的三列）。
     *
     * <p>不用 {@code Map<String,Object>}：Map 的键名绑在列标签上，改一次 SQL 别名就可能悄悄读不到；
     * 映射成明确的属性（列名蛇形 + {@code map-underscore-to-camel-case}）才会写错就报错。
     * 放在 Mapper 内部而不是 application：这是持久层拥有的读模型，依赖方向只能从上往下。
     */
    @Getter
    @Setter
    class BucketSum {

        private Long customerId;
        private Long sumPoints;
        private Long sumFrozen;
    }

    /** 账户游标批次：{@code id > afterId} 而不是 {@code limit offset}，深翻页不会越翻越慢。 */
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

    /**
     * 一批客户的两栏流水之和。
     *
     * <p>类型集合由 {@code PointTxnType.bucket()} 在 Java 侧算好后传进来，<b>不在 SQL 里写死枚举串</b>
     * （第 12 刀的结论：新增类型时忘了归类，账平校验会静默漏算那一笔，比不过校验更危险）。
     * 没有任何流水的客户不会出现在结果里 —— 调用方必须把"缺失"当成 0，而不是跳过。
     */
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

    /**
     * 不变量 4 的真判据：<b>订单已终态而押金还挂着</b>。
     *
     * <p>只数 {@code status in (HELD, FAILED)} 是错的：活动中的单本来就该有一条 HELD，
     * 那样报出来的"悬挂押金"等于在线单数，几百条假阳性会把真问题埋掉（本刀实测到的口径错误）。
     */
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

    /**
     * 悬挂押单的订单号（给押金重试任务用）。
     *
     * <p>判据与 {@link #hangingDeposits} 一字不差：两处口径不同就会出现“报告说有 30 条，
     * 作业只处理了 12 条”这种永远说不清的差异。
     */
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

    /**
     * 不变量 2 的漂移：活动单占着格口，格口却是 FREE（或格口不存在）。
     *
     * <p><b>判据只能写 FREE</b>：活动单的格口合法状态是 RESERVED（未投件）与 OCCUPIED（已投件），
     * 而 FAULT / MAINTENANCE 也可以带着存量单（防呆 8：“标故障后不再被分配，存量单不受影响”）。
     * 写成 {@code status <> 'OCCUPIED'} 会把每一张“正在用”的单报成漂移，
     * 也会把真被标故障的柜机当成事故——假阳性会把真问题埋掉（本刀实测）。
     *
     * <p>一条 SQL 而不是"取全部活动单再逐个 selectById"：日均 3 万单时后者是几万条查询，
     * 对账作业自己会变成 N+1 事故的样本。
     */
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

    /** 逾期看管已收口、但仍没终态的单（滞留清单，等人处置）。 */
    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            select count(*)
              from biz_storage_order
             where deleted = 0 and tenant_id = #{tenantId} and status = 'EXPIRED'
            """)
    long strandedOrders(@Param("tenantId") Long tenantId);

    /**
     * 反向漂移：格口标着 RESERVED/OCCUPIED，却没有任何活动单指向它（格口被永久锁死的形态）。
     *
     * <p>这一类比“有单无位”隐蔽：它不影响正确性但直接少卖，而且没有报错——
     * 运营看到的是“这个格口一直是黄的但就是没人能用”。
     */
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

    /**
     * 通道已收款，但点数没入账（用户付了钱没拿到东西，最恶性的方向）。
     *
     * <p>两面比而不是只查一面：只查“账平不平”永远发现不了这件事，
     * 因为余额与流水是自洽的——<b>错在钱进没进来，不在加减对不对</b>。
     */
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

    /**
     * 点数已入账，却没有对应的通道收款记录（凭空造点，资损方向是平台）。
     *
     * <p><b>只判 {@code RC} 开头的幂等号</b>：不是所有入账都来自通道（后台手工补点、
     * 测试预置都直接走 recharge()）。不加这个限定，对账会把合法的内部入账全报成差异——
     * 判据写宽就是把真问题埋进假阳性（本刀已不止一次踩过这条）。
     */
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
