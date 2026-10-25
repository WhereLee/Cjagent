package com.wherelee.cabinet.device.infra;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.device.domain.BizItemEvidence;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface BizItemEvidenceMapper extends BaseMapper<BizItemEvidence> {

    @Select("""
            select id, tenant_id, order_id, cabinet_id, compartment_id, source, presence,
                   confidence, photo_ref, moment, ai_label, note, create_time, update_time, deleted
              from biz_item_evidence
             where compartment_id = #{compartmentId} and deleted = 0
             order by create_time desc
             limit #{limit}
            """)
    List<BizItemEvidence> timelineOf(@Param("compartmentId") Long compartmentId, @Param("limit") int limit);

    @Select("""
            select count(*) from biz_item_evidence infra
             where infra.deleted = 0 and infra.source = 'INFRARED' and infra.presence = 'PRESENT'
               and exists (select 1 from biz_item_evidence ai
                            where ai.order_id = infra.order_id and ai.deleted = 0
                              and ai.source = 'AI' and ai.presence = 'ABSENT'
                              and ai.create_time > infra.create_time)
            """)
    int infraredMismatchCount();

    @Select("select count(*) from biz_item_evidence where deleted = 0 and source = 'INFRARED' and presence = 'PRESENT'")
    int infraredPresentCount();
}
