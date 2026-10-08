package com.wherelee.cabinet.infrastructure.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wherelee.cabinet.domain.entity.BizCustomer;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 寄存客户账号读写。
 */
@Mapper
public interface BizCustomerMapper extends BaseMapper<BizCustomer> {

    /**
     * 按 openid 定位客户。
     *
     * <p><b>这里必须跳过租户注入</b>：登录那一刻我们还不知道请求属于哪个租户
     * （openid 全局唯一，正是靠它反查出 tenant_id），若让拦截器加上 {@code tenant_id = ?}，
     * 条件里的租户是 null，结果永远是查不到人。
     *
     * <p>安全性靠三点保证：① openid 由微信 code2session 返回，客户端伪造不了；
     * ② uk(open_id) 全局唯一，最多定位到一个账号；③ 查到后立刻把该账号的 tenant_id
     * 写进 TenantContext，后续所有查询回到正常隔离轨道。
     */
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
