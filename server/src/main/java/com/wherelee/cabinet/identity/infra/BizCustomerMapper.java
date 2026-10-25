package com.wherelee.cabinet.identity.infra;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.identity.domain.BizCustomer;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface BizCustomerMapper extends BaseMapper<BizCustomer> {

    @InterceptorIgnore(tenantLine = "true")
    @Select("""
            select id, tenant_id, open_id, union_id, phone, nickname, status,
                   register_time, last_login_at, create_time, update_time, deleted
            from biz_customer
            where open_id = #{openId} and deleted = 0
            """)
    BizCustomer selectByOpenId(@Param("openId") String openId);

    @Update("update biz_customer set last_login_at = NOW(3) where id = #{id} and deleted = 0")
    int touchLastLogin(@Param("id") Long id);
}
