package com.wherelee.cabinet.infrastructure.security;

import com.wherelee.cabinet.domain.entity.BizCustomer;
import com.wherelee.cabinet.infrastructure.mapper.BizCustomerMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 用户端权限装载：固定 {@code ROLE_CUSTOMER}，账号不存在或被冻结时返回<b>空权限</b>。
 *
 * <p>用户端不参与 RBAC（C 端没有角色配置），但每次请求仍要查一次账号状态，
 * 否则"冻结客户"要等 access token 自然过期（默认 30 分钟）才真正失效。
 *
 * <p>返回空权限的效果是 403（已认证但无权限），不是 401——语义上更准确：
 * 凭证本身是真的，只是账号不再被允许使用。要立刻失效请配合注销/黑名单。
 *
 * <p>这里的查询发生在过滤器已建立的租户上下文内，所以 {@code selectById} 自带
 * {@code tenant_id} 条件，跨租户拿到别人的客户 ID 也查不出记录。
 */
@Service
public class CustomerAuthoritiesResolver implements AuthoritiesResolver {

    public static final String CUSTOMER_ROLE = "ROLE_CUSTOMER";

    private final BizCustomerMapper customerMapper;

    public CustomerAuthoritiesResolver(BizCustomerMapper customerMapper) {
        this.customerMapper = customerMapper;
    }

    @Override
    public List<String> resolve(Long subjectId) {
        BizCustomer customer = customerMapper.selectById(subjectId);
        if (customer == null || !Integer.valueOf(1).equals(customer.getStatus())) {
            return List.of();
        }
        return List.of(CUSTOMER_ROLE);
    }
}
