package com.wherelee.cabinet.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.domain.entity.BizPriceRule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 计价策略 Mapper。
 *
 * <p>「取当前生效」是每次下单都会走的一跳，所以它必须是一条能吃完索引的点查：
 * {@code idx_price_scope_live (tenant_id, site_id, status, effective_at)} + limit 1，
 * 不是"把历史都捞出来在 Java 里挑"。
 */
@Mapper
public interface BizPriceRuleMapper extends BaseMapper<BizPriceRule> {

    String COLUMNS = "id, tenant_id, site_id, version, status, deposit_points, free_minutes, "
            + "daily_cap_hours, cap_days, tolerance_minutes, remote_close_hours, "
            + "unit_small, unit_medium, unit_large, effective_at, published_by, reason, "
            + "create_time, update_time, deleted";

    /**
     * 当前生效的策略：站点优先，回退全局。
     *
     * <p>排序键 {@code (site_id is null)} 让"带站点的行"排在"全局行"前面，再按生效时刻倒序取一条：
     * 一次查询就把"灰度优先 + 到点切换"两件事都表达清楚了，也不需要先发两条 SQL 再在内存里比。
     */
    @Select("""
            select ${@com.wherelee.cabinet.infrastructure.mapper.BizPriceRuleMapper@COLUMNS}
              from biz_price_rule
             where deleted = 0
               and status = 'LIVE'
               and effective_at <= now(3)
               and (site_id = #{siteId} or site_id is null)
             order by (site_id is null), effective_at desc, version desc
             limit 1
            """)
    BizPriceRule selectEffective(@Param("siteId") Long siteId);

    /**
     * 下一版本号。这里需要“NULL 安全等值”，但**不能用 MySQL 的 {@code <=>}**：
     * 本表的语句都要过 JSqlParser（租户插件与防全表更新插件都要解析），而它认不了这个运算符，
     * 报错是“Failed to process, Error SQL”而不是一个提示你换写法的信息。
     * 改用哨兵值比较：点位 ID 不可能为 -1，所以下面等价且可解析。
     * （发布是低频写路径，这几条不为了索引而牺牲可解析性；真正每次都跑的“取当前生效”仍走索引。）
     */
    @Select("""
            select coalesce(max(version), 0) + 1 from biz_price_rule
             where deleted = 0 and coalesce(site_id, -1) = coalesce(#{siteId}, -1)
            """)
    int nextVersion(@Param("siteId") Long siteId);

    /**
     * 把同一范围内"已经生效过"的旧策略标为已替代，保留未来生效的那些。
     *
     * <p>{@code effective_at <= now(3)} 这个条件是这条链路的要害：漏了它，零点切换的灰度单
     * 会在发布的瞬间被标成 SUPERSEDED，到点时该范围内一条策略都不剩，下单直接退回到 yml 默认价。
     * 而漏掉这一步不会当场报错——只会在某天零点悄悄换错价。
     */
    @Update("""
            update biz_price_rule set status = 'SUPERSEDED', update_time = now(3)
             where deleted = 0 and status = 'LIVE'
               and coalesce(site_id, -1) = coalesce(#{siteId}, -1)
               and effective_at <= now(3)
               and id <> #{keepId}
            """)
    int supersedeCurrent(@Param("siteId") Long siteId, @Param("keepId") Long keepId);

    /** 同一范围内的历史版本（新→旧），页面展示与回滚取内容都读它。 */
    @Select("""
            select ${@com.wherelee.cabinet.infrastructure.mapper.BizPriceRuleMapper@COLUMNS}
              from biz_price_rule
             where deleted = 0 and coalesce(site_id, -1) = coalesce(#{siteId}, -1)
             order by version desc
            """)
    List<BizPriceRule> historyOf(@Param("siteId") Long siteId);

    /** 全站（含各点位）的策略总览，给后台列表页。 */
    @Select("""
            select ${@com.wherelee.cabinet.infrastructure.mapper.BizPriceRuleMapper@COLUMNS}
              from biz_price_rule
             where deleted = 0
             order by site_id is null desc, site_id, version desc
            """)
    List<BizPriceRule> listAll();
}
