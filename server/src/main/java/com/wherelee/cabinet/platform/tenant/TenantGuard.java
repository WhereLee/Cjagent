package com.wherelee.cabinet.platform.tenant;

import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.entity.SysTenant;
import com.wherelee.cabinet.infrastructure.mapper.SysTenantMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

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

    public SysTenant requireUsableById(Long tenantId) {
        SysTenant tenant = tenantMapper.selectById(tenantId);
        if (tenant == null) {
            throw new BizException(ResultCode.TENANT_INVALID, "租户不存在");
        }
        assertUsable(tenant);
        return tenant;
    }
}
