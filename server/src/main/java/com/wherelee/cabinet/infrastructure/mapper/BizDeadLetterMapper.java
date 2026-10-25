package com.wherelee.cabinet.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.domain.entity.BizDeadLetter;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface BizDeadLetterMapper extends BaseMapper<BizDeadLetter> {

    @Insert("""
            insert into biz_dead_letter (id, tenant_id, topic, tag, dlq_topic, msg_key, payload, error,
                                         reconsume_times, status, received_at, create_time, update_time, deleted)
            values (#{id}, #{tenantId}, #{topic}, #{tag}, #{dlqTopic}, #{msgKey}, #{payload}, #{error},
                    #{reconsumeTimes}, 'OPEN', now(3), now(3), now(3), 0)
            on duplicate key update
                error = values(error),
                reconsume_times = values(reconsume_times),
                update_time = now(3)
            """)
    int append(@Param("id") Long id,
               @Param("tenantId") Long tenantId,
               @Param("topic") String topic,
               @Param("tag") String tag,
               @Param("dlqTopic") String dlqTopic,
               @Param("msgKey") String msgKey,
               @Param("payload") String payload,
               @Param("error") String error,
               @Param("reconsumeTimes") int reconsumeTimes);

    @Update("""
            update biz_dead_letter
               set status = 'REPLAYED', replayed_at = now(3), update_time = now(3)
             where id = #{id} and status = 'OPEN' and deleted = 0
            """)
    int markReplayed(@Param("id") Long id);
}
