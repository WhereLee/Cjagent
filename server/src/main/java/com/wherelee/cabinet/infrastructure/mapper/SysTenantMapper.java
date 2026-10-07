package com.wherelee.cabinet.infrastructure.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.domain.entity.SysTenant;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 租户查询。sys_tenant 在租户拦截器白名单内（见 {@code CabinetTenantHandler}），
 * 因此这些 SQL 不需要租户上下文也能执行。
 */
public interface SysTenantMapper extends BaseMapper<SysTenant> {

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            select id, tenant_code, name, status, expire_date, create_time, update_time, deleted
            from sys_tenant
            where tenant_code = #{tenantCode} and deleted = 0
            """)
    SysTenant selectByCode(@Param("tenantCode") String tenantCode);
}
