package com.wherelee.cabinet.infrastructure.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface BizArchiveMapper {

    String FOREACH = "<foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach>";

    @Select("""
            select id from biz_device_report
             where create_time < date_sub(now(3), interval #{days} day)
             order by id
             limit #{batch}
            """)
    List<Long> pickReportIds(@Param("days") int days, @Param("batch") int batch);

    @Insert("<script>insert ignore into biz_device_report_archive "
            + "(id, tenant_id, cabinet_id, slot_id, order_id, seq, event_type, dedup_key, "
            + "payload, reported_at, received_at, create_time, archived_at) "
            + "select id, tenant_id, cabinet_id, slot_id, order_id, seq, event_type, dedup_key, "
            + "payload, reported_at, received_at, create_time, now(3) from biz_device_report "
            + "where id in " + FOREACH + "</script>")
    int copyReports(@Param("ids") List<Long> ids);

    @Delete("<script>delete from biz_device_report "
            + "where create_time &lt; date_sub(now(3), interval #{days} day) and id in " + FOREACH
            + " and exists (select 1 from biz_device_report_archive a where a.id = biz_device_report.id)"
            + "</script>")
    int deleteArchivedReports(@Param("ids") List<Long> ids, @Param("days") int days);

    @Select("""
            select id from biz_delay_task
             where status in ('DONE', 'DEAD', 'CANCELLED')
               and create_time < date_sub(now(3), interval #{days} day)
             order by id
             limit #{batch}
            """)
    List<Long> pickTaskIds(@Param("days") int days, @Param("batch") int batch);

    @Insert("<script>insert ignore into biz_delay_task_archive "
            + "(id, tenant_id, task_type, biz_key, fire_at, status, attempt, last_error, "
            + "lease_owner, lease_expire_at, create_time, update_time, deleted, archived_at) "
            + "select id, tenant_id, task_type, biz_key, fire_at, status, attempt, last_error, "
            + "lease_owner, lease_expire_at, create_time, update_time, deleted, now(3) from biz_delay_task "
            + "where id in " + FOREACH + "</script>")
    int copyTasks(@Param("ids") List<Long> ids);

    @Delete("<script>delete from biz_delay_task "
            + "where status in ('DONE', 'DEAD', 'CANCELLED') "
            + "and create_time &lt; date_sub(now(3), interval #{days} day) and id in " + FOREACH
            + " and exists (select 1 from biz_delay_task_archive a where a.id = biz_delay_task.id)"
            + "</script>")
    int deleteArchivedTasks(@Param("ids") List<Long> ids, @Param("days") int days);
}
