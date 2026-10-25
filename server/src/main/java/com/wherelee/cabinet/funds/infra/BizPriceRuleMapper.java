package com.wherelee.cabinet.funds.infra;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.funds.domain.BizPriceRule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface BizPriceRuleMapper extends BaseMapper<BizPriceRule> {

    String COLUMNS = "id, tenant_id, site_id, version, status, deposit_points, free_minutes, "
            + "daily_cap_hours, cap_days, tolerance_minutes, remote_close_hours, "
            + "unit_small, unit_medium, unit_large, effective_at, published_by, reason, "
            + "create_time, update_time, deleted";

    @Select("""
            select ${@com.wherelee.cabinet.funds.infra.BizPriceRuleMapper@COLUMNS}
              from biz_price_rule
             where deleted = 0
               and status = 'LIVE'
               and effective_at <= now(3)
               and (site_id = #{siteId} or site_id is null)
             order by (site_id is null), effective_at desc, version desc
             limit 1
            """)
    BizPriceRule selectEffective(@Param("siteId") Long siteId);

    @Select("""
            select coalesce(max(version), 0) + 1 from biz_price_rule
             where deleted = 0 and coalesce(site_id, -1) = coalesce(#{siteId}, -1)
            """)
    int nextVersion(@Param("siteId") Long siteId);

    @Update("""
            update biz_price_rule set status = 'SUPERSEDED', update_time = now(3)
             where deleted = 0 and status = 'LIVE'
               and coalesce(site_id, -1) = coalesce(#{siteId}, -1)
               and effective_at <= now(3)
               and id <> #{keepId}
            """)
    int supersedeCurrent(@Param("siteId") Long siteId, @Param("keepId") Long keepId);

    @Select("""
            select ${@com.wherelee.cabinet.funds.infra.BizPriceRuleMapper@COLUMNS}
              from biz_price_rule
             where deleted = 0 and coalesce(site_id, -1) = coalesce(#{siteId}, -1)
             order by version desc
            """)
    List<BizPriceRule> historyOf(@Param("siteId") Long siteId);

    @Select("""
            select ${@com.wherelee.cabinet.funds.infra.BizPriceRuleMapper@COLUMNS}
              from biz_price_rule
             where deleted = 0
             order by site_id is null desc, site_id, version desc
            """)
    List<BizPriceRule> listAll();
}
