package com.wherelee.cabinet.device.infra;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.device.domain.BizDeviceCommand;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BizDeviceCommandMapper extends BaseMapper<BizDeviceCommand> {

    @Select("""
            select id, tenant_id, order_id, cabinet_id, slot_id, request_id, action, status,
                   retry_count, operator_id, sent_at, acked_at, last_error, version,
                   create_time, update_time, deleted
            from biz_device_command
            where request_id = #{requestId} and deleted = 0
            """)
    BizDeviceCommand selectByRequestId(@Param("requestId") String requestId);

    @Select("""
            select request_id from biz_device_command
            where deleted = 0
              and status in ('SENT', 'ACKED', 'TIMEOUT')
              and sent_at < #{olderThan}
            order by sent_at
            limit #{limit}
            """)
    java.util.List<String> findStuckRequestIds(@Param("olderThan") java.time.LocalDateTime olderThan,
                                               @Param("limit") int limit);
}
