package com.wherelee.cabinet.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.domain.entity.BizDeadLetter;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 死信归档表。
 *
 * <p>这张表在 {@code CabinetTenantHandler} 的白名单里（运维数据、不分租户），
 * 所以这里不需要 {@code @InterceptorIgnore}——对照 {@code BizOutboxMapper}：那张表有
 * {@code tenant_id}、不在白名单，跨租户扫描才要显式忽略。
 */
@Mapper
public interface BizDeadLetterMapper extends BaseMapper<BizDeadLetter> {

    /**
     * 归档一条死信。
     *
     * <p>用 upsert 而不是纯 insert：同一条消息可能被多次投进死信（比如人工重放后又失败），
     * 每次失败都该刷新原因与重试次数，而不是插出第二行。
     * 注意 {@code msg_key} 为 NULL 时不触发唯一键（MySQL 语义），坏消息不会被合并掉——这是想要的。
     */
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

    /**
     * 标记已重放。
     *
     * <p>{@code and status = 'OPEN'} 是幂等的一部分：两次点击重放不会把状态改两次，
     * 第二次影响行数为 0，调用方据此提示"已经处理过"。
     */
    @Update("""
            update biz_dead_letter
               set status = 'REPLAYED', replayed_at = now(3), update_time = now(3)
             where id = #{id} and status = 'OPEN' and deleted = 0
            """)
    int markReplayed(@Param("id") Long id);
}
