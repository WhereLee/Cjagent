package com.wherelee.cabinet.infrastructure.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 冷热分层搬运（A-5）。
 *
 * <p>全部方法都 {@code @InterceptorIgnore(tenantLine)}：归档按时间跨租户批量搬，
 * 而执行它的线程本来就没有租户上下文（带租户条件的 SQL 在这里会被守卫直接拒掉，
 * 一行都搬不动）。这与调度器取到期任务用的是同一套做法。
 *
 * <p>删除语句都带“归档表里已存在”的条件。<b>先插后删是数据安全的全部依赖</b>，
 * 所以删除必须能证明那一行已经在归档表里，而不是相信“上一步大概插入成功了”。
 *
 * <p>带 {@code <foreach>} 的语句必须整体包在 {@code <script>} 里，否则 MyBatis
 * 会把标签当普通文本拼进 SQL（表现为语法错，而不是"少了几行"）。
 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface BizArchiveMapper {

    String FOREACH = "<foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach>";

    // ---------------------------------------------------------------- 上报流水

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

    // ---------------------------------------------------------------- 延迟任务

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
