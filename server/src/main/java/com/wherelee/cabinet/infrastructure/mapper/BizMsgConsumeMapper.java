package com.wherelee.cabinet.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.domain.entity.BizMsgConsume;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 消费幂等记录读写。本表在租户白名单内，条件要自己写（拦截器不会加 tenant_id）。
 */
@Mapper
public interface BizMsgConsumeMapper extends BaseMapper<BizMsgConsume> {

    @Select("select status from biz_msg_consume where topic = #{topic} and msg_key = #{msgKey}")
    String selectStatus(@Param("topic") String topic, @Param("msgKey") String msgKey);

    /**
     * 状态推进。{@code and status <> 'PROCESSED'} 是关键：
     * 已成功的记录不允许被后续重投写回 FAILED，否则一次网络抖动就会把_done_ 改成 _待办_，
     * 让人对着一条早就处理完的消息反复排查。
     */
    @Update("""
            update biz_msg_consume
               set status = #{status},
                   attempt = attempt + 1,
                   last_error = #{error},
                   trace_id = coalesce(#{traceId}, trace_id),
                   update_time = NOW(3)
             where topic = #{topic} and msg_key = #{msgKey} and status <> 'PROCESSED'
            """)
    int markStatus(@Param("topic") String topic,
                   @Param("msgKey") String msgKey,
                   @Param("status") String status,
                   @Param("error") String error,
                   @Param("traceId") String traceId);
}
