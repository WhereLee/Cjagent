package com.wherelee.cabinet.infrastructure.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.domain.entity.BizAiJob;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface BizAiJobMapper extends BaseMapper<BizAiJob> {

    @InterceptorIgnore(tenantLine = "true")
    @Insert("""
            insert into biz_ai_job
                (id, tenant_id, job_type, request_key, requester_id, payload, status, active_flag,
                 attempt, create_time, update_time, deleted)
            values (#{id}, #{tenantId}, #{jobType}, #{requestKey}, #{requesterId}, #{payload},
                    'SUBMITTED', 1, 0, now(3), now(3), 0)
            on duplicate key update active_flag = active_flag
            """)
    int append(@Param("id") Long id,
               @Param("tenantId") Long tenantId,
               @Param("jobType") String jobType,
               @Param("requestKey") String requestKey,
               @Param("requesterId") Long requesterId,
               @Param("payload") String payload);

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            select id from biz_ai_job
             where deleted = 0 and tenant_id = #{tenantId} and job_type = #{jobType}
               and request_key = #{requestKey} and requester_id = #{requesterId} and active_flag = 1
             limit 1
            """)
    Long findActiveId(@Param("tenantId") Long tenantId,
                      @Param("jobType") String jobType,
                      @Param("requestKey") String requestKey,
                      @Param("requesterId") Long requesterId);

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            select id from biz_ai_job
             where deleted = 0 and active_flag = 1
               and (status = 'SUBMITTED' or (status = 'RUNNING' and lease_expire_at < now(3)))
             order by create_time
             limit #{limit}
            """)
    List<Long> findDueIds(@Param("limit") int limit);

    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            update biz_ai_job
               set status = 'RUNNING', lease_owner = #{owner}, lease_expire_at = #{leaseUntil},
                   attempt = attempt + 1, update_time = now(3)
             where id = #{id} and deleted = 0
               and (status = 'SUBMITTED' or (status = 'RUNNING' and lease_expire_at < now(3)))
            """)
    int claim(@Param("id") Long id, @Param("owner") String owner, @Param("leaseUntil") LocalDateTime leaseUntil);

    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            update biz_ai_job
               set status = 'DONE', result = #{result}, last_error = null, active_flag = null,
                   lease_owner = null, lease_expire_at = null, update_time = now(3)
             where id = #{id} and lease_owner = #{owner} and status = 'RUNNING' and deleted = 0
            """)
    int markDone(@Param("id") Long id, @Param("owner") String owner, @Param("result") String result);

    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            update biz_ai_job
               set status = 'SUBMITTED', last_error = #{error},
                   lease_owner = null, lease_expire_at = null, update_time = now(3)
             where id = #{id} and lease_owner = #{owner} and status = 'RUNNING' and deleted = 0
            """)
    int markRetry(@Param("id") Long id, @Param("owner") String owner, @Param("error") String error);

    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            update biz_ai_job
               set status = 'FAILED', last_error = #{error}, active_flag = null,
                   lease_owner = null, lease_expire_at = null, update_time = now(3)
             where id = #{id} and lease_owner = #{owner} and status = 'RUNNING' and deleted = 0
            """)
    int markFailed(@Param("id") Long id, @Param("owner") String owner, @Param("error") String error);

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            select id, tenant_id, job_type, request_key, requester_id, payload, status, active_flag,
                   result, last_error, attempt, lease_owner, lease_expire_at,
                   create_time, update_time, deleted
            from biz_ai_job where id = #{id} and deleted = 0
            """)
    BizAiJob selectForWorker(@Param("id") Long id);
}
