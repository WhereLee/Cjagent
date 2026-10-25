package com.wherelee.cabinet.infrastructure.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.domain.entity.BizOutbox;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface BizOutboxMapper extends BaseMapper<BizOutbox> {

    @InterceptorIgnore(tenantLine = "true")
    @Insert("""
            insert into biz_outbox
                (id, tenant_id, event_type, dedup_key, payload, status, attempt, next_attempt_at,
                 create_time, update_time, deleted)
            values (#{id}, #{tenantId}, #{eventType}, #{dedupKey}, #{payload}, 'PENDING', 0, now(3),
                    now(3), now(3), 0)
            on duplicate key update status = status
            """)
    int append(@Param("id") Long id,
               @Param("tenantId") Long tenantId,
               @Param("eventType") String eventType,
               @Param("dedupKey") String dedupKey,
               @Param("payload") String payload);

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            select id from biz_outbox
             where deleted = 0 and status in ('PENDING','FAILED') and next_attempt_at <= now(3)
             order by next_attempt_at
             limit #{limit}
            """)
    List<Long> findDueIds(@Param("limit") int limit);

    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            update biz_outbox
               set next_attempt_at = #{nextAttemptAt}, attempt = attempt + 1, update_time = now(3)
             where id = #{id} and deleted = 0
               and status in ('PENDING','FAILED') and next_attempt_at <= now(3)
            """)
    int claim(@Param("id") Long id, @Param("nextAttemptAt") LocalDateTime nextAttemptAt);

    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            update biz_outbox
               set status = 'DONE', done_at = now(3), last_error = null, update_time = now(3)
             where id = #{id} and deleted = 0 and status in ('PENDING','FAILED')
            """)
    int markDone(@Param("id") Long id);

    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            update biz_outbox
               set status = #{status}, next_attempt_at = #{nextAttemptAt}, last_error = #{error},
                   update_time = now(3)
             where id = #{id} and deleted = 0 and status in ('PENDING','FAILED')
            """)
    int markOutcome(@Param("id") Long id,
                    @Param("status") String status,
                    @Param("nextAttemptAt") LocalDateTime nextAttemptAt,
                    @Param("error") String error);

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            select id, tenant_id, event_type, dedup_key, payload, status, attempt, next_attempt_at,
                   last_error, done_at, create_time, update_time, deleted
            from biz_outbox
            where id = #{id} and deleted = 0
            """)
    BizOutbox selectForWorker(@Param("id") Long id);
}
