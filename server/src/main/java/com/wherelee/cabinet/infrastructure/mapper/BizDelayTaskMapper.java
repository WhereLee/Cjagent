package com.wherelee.cabinet.infrastructure.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.domain.entity.BizDelayTask;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface BizDelayTaskMapper extends BaseMapper<BizDelayTask> {

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            select id from biz_delay_task
             where deleted = 0 and tenant_id = #{tenantId}
               and ((status in ('PENDING','FAILED') and fire_at <= now(3))
                 or (status = 'RUNNING' and lease_expire_at < now(3)))
             order by fire_at
             limit #{limit}
             for update skip locked
            """)
    List<Long> findDueIdsForUpdateSkip(@Param("tenantId") Long tenantId, @Param("limit") int limit);

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            select distinct tenant_id from biz_delay_task
             where deleted = 0
               and ((status in ('PENDING','FAILED') and fire_at <= now(3))
                 or (status = 'RUNNING' and lease_expire_at < now(3)))
             order by tenant_id
            """)
    List<Long> findDueTenants();

    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            update biz_delay_task
               set status = 'RUNNING', lease_owner = #{owner}, lease_expire_at = #{leaseUntil},
                   attempt = attempt + 1, update_time = now(3)
             where id = #{id} and deleted = 0
               and ((status in ('PENDING','FAILED') and fire_at <= now(3))
                 or (status = 'RUNNING' and lease_expire_at < now(3)))
            """)
    int claim(@Param("id") Long id, @Param("owner") String owner, @Param("leaseUntil") LocalDateTime leaseUntil);

    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            update biz_delay_task
               set status = 'DONE', last_error = null, lease_owner = null, lease_expire_at = null,
                   update_time = now(3)
             where id = #{id} and lease_owner = #{owner} and status = 'RUNNING' and deleted = 0
            """)
    int markDone(@Param("id") Long id, @Param("owner") String owner);

    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            update biz_delay_task
               set status = #{status}, fire_at = #{nextFireAt}, last_error = #{error},
                   lease_owner = null, lease_expire_at = null, update_time = now(3)
             where id = #{id} and lease_owner = #{owner} and status = 'RUNNING' and deleted = 0
            """)
    int markOutcome(@Param("id") Long id,
                    @Param("owner") String owner,
                    @Param("status") String status,
                    @Param("nextFireAt") LocalDateTime nextFireAt,
                    @Param("error") String error);

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            select id, tenant_id, task_type, biz_key, fire_at, status, attempt, last_error,
                   lease_owner, lease_expire_at, create_time, update_time, deleted
            from biz_delay_task
            where id = #{id} and deleted = 0
            """)
    BizDelayTask selectForWorker(@Param("id") Long id);

    @InterceptorIgnore(tenantLine = "true")
    @Insert("""
            insert into biz_delay_task
                (id, tenant_id, task_type, biz_key, fire_at, status, attempt, create_time, update_time, deleted)
            values (#{id}, #{tenantId}, #{taskType}, #{bizKey}, #{fireAt}, 'PENDING', 0, now(3), now(3), 0)
            on duplicate key update
                attempt = case when status = 'DONE' then 0 else attempt end,
                fire_at = case when status = 'DONE' then values(fire_at)
                               when status in ('PENDING','FAILED') then least(fire_at, values(fire_at))
                               else fire_at end,
                status = case when status = 'DONE' then 'PENDING' else status end,
                update_time = now(3)
            """)
    int register(@Param("id") Long id,
                 @Param("tenantId") Long tenantId,
                 @Param("taskType") String taskType,
                 @Param("bizKey") String bizKey,
                 @Param("fireAt") LocalDateTime fireAt);

    @InterceptorIgnore(tenantLine = "true")
    @Update("""
            update biz_delay_task
               set status = 'DONE', last_error = 'CANCELLED', update_time = now(3)
             where tenant_id = #{tenantId} and task_type = #{taskType} and biz_key = #{bizKey}
               and status in ('PENDING','FAILED') and deleted = 0
            """)
    int cancel(@Param("tenantId") Long tenantId,
               @Param("taskType") String taskType,
               @Param("bizKey") String bizKey);

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            select count(*) from biz_delay_task
             where deleted = 0 and status in ('PENDING','FAILED')
               and fire_at <= date_add(now(3), interval 1 hour)
            """)
    int countDueBacklog();
}
