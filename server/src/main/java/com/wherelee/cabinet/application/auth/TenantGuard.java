package com.wherelee.cabinet.application.auth;

import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.entity.SysTenant;
import com.wherelee.cabinet.infrastructure.mapper.SysTenantMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * 租户可用性判定：存在、启用、未过期。
 *
 * <p>两端登录都要这道关，所以单独一处实现。sys_tenant 在租户拦截器白名单里，
 * 因此这里<b>不需要</b>租户上下文（有反而会把自己过滤没）。
 *
 * <p>用 40301（{@code TENANT_INVALID}）而不是 40100：区别在于"凭证没问题，是这个租户
 * 不再被允许使用"，前端应该提示"联系管理员"而不是"重新登录"。
 */
@Service
public class TenantGuard {

    private static final Logger log = LoggerFactory.getLogger(TenantGuard.class);

    private final SysTenantMapper tenantMapper;

    public TenantGuard(SysTenantMapper tenantMapper) {
        this.tenantMapper = tenantMapper;
    }

    public SysTenant requireUsableByCode(String tenantCode) {
        SysTenant tenant = tenantMapper.selectByCode(tenantCode);
        if (tenant == null) {
            log.warn("登录被拒：租户编码不存在 code={}", tenantCode);
            throw new BizException(ResultCode.TENANT_INVALID, "租户不存在");
        }
        assertUsable(tenant);
        return tenant;
    }

    public void assertUsable(SysTenant tenant) {
        if (!Integer.valueOf(1).equals(tenant.getStatus())) {
            throw new BizException(ResultCode.TENANT_INVALID, "租户已停用，请联系管理员");
        }
        if (tenant.getExpireDate() != null && tenant.getExpireDate().isBefore(LocalDate.now())) {
            throw new BizException(ResultCode.TENANT_INVALID, "租户服务已到期，请联系管理员");
        }
    }

    /** 按 ID 查（骑手已归属某租户时用得上），不存在或不可用同样拒绝。 */
    public SysTenant requireUsableById(Long tenantId) {
        SysTenant tenant = tenantMapper.selectById(tenantId);
        if (tenant == null) {
            throw new BizException(ResultCode.TENANT_INVALID, "租户不存在");
        }
        assertUsable(tenant);
        return tenant;
    }
}
