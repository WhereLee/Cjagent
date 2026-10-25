package com.wherelee.cabinet.infrastructure.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface ProbeAuditQueryMapper {

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
