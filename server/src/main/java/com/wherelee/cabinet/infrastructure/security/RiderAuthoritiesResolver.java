package com.wherelee.cabinet.infrastructure.security;

import com.wherelee.cabinet.domain.entity.SysRider;
import com.wherelee.cabinet.infrastructure.mapper.SysRiderMapper;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 骑手端权限装载：固定 {@code ROLE_RIDER}，账号不存在或被冻结时返回<b>空权限</b>。
 *
 * <p>骑手端不参与 RBAC（C 端用户没有角色配置），但每次请求仍要查一次账号状态，
 * 否则"冻结骑手"要等 access token 自然过期（默认 30 分钟）才真正失效。
 *
 * <p>返回空权限的效果是 403（已认证但无权限），不是 401 —— 语义上更准确：
 * 凭证本身是真的，只是账号不再被允许使用。要立刻失效请配合注销/黑名单。
 *
 * <p>这里的查询发生在过滤器已建立的租户上下文内，所以 {@code selectById} 自带
 * {@code tenant_id} 条件，跨租户拿到别人骑手 ID 也查不出记录。
 */
@Service
public class RiderAuthoritiesResolver implements AuthoritiesResolver {

    public static final String RIDER_ROLE = "ROLE_RIDER";

    private final SysRiderMapper riderMapper;

    public RiderAuthoritiesResolver(SysRiderMapper riderMapper) {
        this.riderMapper = riderMapper;
    }

    @Override
    public List<String> resolve(Long subjectId) {
        SysRider rider = riderMapper.selectById(subjectId);
        if (rider == null || !Integer.valueOf(1).equals(rider.getStatus())) {
            return List.of();
        }
        return List.of(RIDER_ROLE);
    }
}
