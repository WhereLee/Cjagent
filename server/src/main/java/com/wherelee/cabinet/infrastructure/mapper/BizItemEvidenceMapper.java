package com.wherelee.cabinet.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.domain.entity.BizItemEvidence;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 物检证据的读侧。
 *
 * <p>只有查询没有更新方法：<b>证据表故意做成只增</b>，一旦给了 UPDATE，
 * 下一次改代码的人就会顺手"修正"历史结论，纠纷处理随即失去唯一能服人的东西
 * （与点数流水同一取向：余额可以重算，历史不可改）。
 */
@Mapper
public interface BizItemEvidenceMapper extends BaseMapper<BizItemEvidence> {

    /** 某个格口的观测时间线（新→旧）。客服/运维看的就是这条。 */
    @Select("""
            select id, tenant_id, order_id, cabinet_id, compartment_id, source, presence,
                   confidence, photo_ref, note, create_time, update_time, deleted
              from biz_item_evidence
             where compartment_id = #{compartmentId} and deleted = 0
             order by create_time desc
             limit #{limit}
            """)
    List<BizItemEvidence> timelineOf(@Param("compartmentId") Long compartmentId, @Param("limit") int limit);

    /**
     * 红外说有、AI 看图说没有的次数——<b>红外误报率的分子</b>。
     *
     * <p>为什么用一条 SQL 而不是"在 Java 里把两条记录查出来比"：这个数每轮对账都要算，
     * 而且是按订单成对比对的（同一单里 INFRARED=PRESENT 且 AI=ABSENT）。
     * 拉回应用层比较会把每单的观测全捞出来，几百倍于必要的读量。
     */
    @Select("""
            select count(*) from biz_item_evidence infra
             where infra.deleted = 0 and infra.source = 'INFRARED' and infra.presence = 'PRESENT'
               and exists (select 1 from biz_item_evidence ai
                            where ai.order_id = infra.order_id and ai.deleted = 0
                              and ai.source = 'AI' and ai.presence = 'ABSENT'
                              and ai.create_time > infra.create_time)
            """)
    int infraredMismatchCount();

    /** 分母：红外一共报过多少次"有物"。误报率 = 上式 / 本式，两个数都要给看板，别只给一个比值。 */
    @Select("select count(*) from biz_item_evidence where deleted = 0 and source = 'INFRARED' and presence = 'PRESENT'")
    int infraredPresentCount();
}
