package com.wherelee.cabinet.infrastructure.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 测试专用：读审计表验证切面写入了什么。
 *
 * <p>故意不放在 main：读审计的正式接口（分页查询、导出）属于后台功能，
 * 现在写进生产代码就是没有调用方的死代码。
 */
public interface ProbeAuditQueryMapper {

    /**
     * 按 traceId 精确取一条审计记录。
     *
     * <p>用 traceId 而不是 count/最新一条：测试并发跑、且审计是 REQUIRES_NEW 独立提交，
     * 按"最新"断言会读到别的用例写的记录（典型的不稳定测试）。
     */
    @Select("""
            select module, operation, request_uri, request_method, success, error_msg,
                   params, cost_ms, trace_id, operator_id, tenant_id
            from sys_operation_log
            where trace_id = #{traceId}
            order by id desc
            limit 1
            """)
    Map<String, Object> selectByTraceId(@Param("traceId") String traceId);

    @Select("select count(*) from sys_operation_log where operation = #{operation}")
    int countByOperation(@Param("operation") String operation);

    @Select("""
            select params from sys_operation_log
            where operation = #{operation}
            order by id desc limit 1
            """)
    List<String> latestParams(@Param("operation") String operation);
}
