package com.wherelee.cabinet.infrastructure.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.domain.entity.BizDeviceCommand;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 设备指令读写。状态推进走实体的 {@code transitTo} + 带 {@code @Version} 的 updateById，
 * 乐观锁拦截器已注册（第 8 刀），所以并发回执不会互相覆盖。
 */
@Mapper
public interface BizDeviceCommandMapper extends BaseMapper<BizDeviceCommand> {

    /**
     * 按幂等键定位指令。
     *
     * <p>这里<b>不能跳过租户过滤</b>：requestId 虽然全局唯一，但"用别人的 requestId 探到别人的指令"
     * 本身就是信息泄露，租户条件由拦截器自动附加。
     *
     * <p><b>列清单里必须带 version</b>：手写列清单最容易漏的就是它，漏了就不报错，
     * 只是拿这条记录去 update 时乐观锁静默失效（version 为 null 时拦截器不加条件）。
     */
    @Select("""
            select id, tenant_id, order_id, cabinet_id, slot_id, request_id, action, status,
                   retry_count, operator_id, sent_at, acked_at, last_error, version,
                   create_time, update_time, deleted
            from biz_device_command
            where request_id = #{requestId} and deleted = 0
            """)
    BizDeviceCommand selectByRequestId(@Param("requestId") String requestId);
}
