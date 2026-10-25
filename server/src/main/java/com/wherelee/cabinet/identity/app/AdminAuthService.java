package com.wherelee.cabinet.identity.app;
import com.wherelee.cabinet.platform.tenant.TenantGuard;

import com.wherelee.cabinet.identity.api.AdminLoginRequest;
import com.wherelee.cabinet.identity.api.CurrentAccountView;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.common.security.AuthConstants;
import com.wherelee.cabinet.platform.security.LoginUser;
import com.wherelee.cabinet.domain.entity.SysTenant;
import com.wherelee.cabinet.infrastructure.mapper.SysUserMapper;
import com.wherelee.cabinet.platform.security.AdminPermissionService;
import com.wherelee.cabinet.platform.security.TokenIssuer;
import com.wherelee.cabinet.platform.security.TokenPair;
import com.wherelee.cabinet.platform.security.Caller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;

@Service
public class AdminAuthService implements com.wherelee.cabinet.identity.api.AdminAuthPort {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthService.class);

    private final TenantGuard tenantGuard;
    private final SysUserMapper userMapper;
    private final AuthenticationManager authenticationManager;
    private final TokenIssuer tokenIssuer;
    private final AdminPermissionService permissionService;

    public AdminAuthService(TenantGuard tenantGuard,
                           SysUserMapper userMapper,
                           AuthenticationManager authenticationManager,
                           TokenIssuer tokenIssuer,
                           AdminPermissionService permissionService) {
        this.tenantGuard = tenantGuard;
        this.userMapper = userMapper;
        this.authenticationManager = authenticationManager;
        this.tokenIssuer = tokenIssuer;
        this.permissionService = permissionService;
    }

    @Override
    public TokenPair login(AdminLoginRequest request) {
        SysTenant tenant = tenantGuard.requireUsableByCode(request.tenantCode());

        LoginUser principal;
        try {
            principal = (LoginUser) TenantContext.callAs(tenant.getId(), () ->
                    authenticationManager.authenticate(
                                    new UsernamePasswordAuthenticationToken(request.username(), request.password()))
                            .getPrincipal());
        } catch (AuthenticationException e) {
            log.warn("后台登录失败 tenant={} username={} 原因={}",
                    request.tenantCode(), request.username(), e.getClass().getSimpleName());
            throw new BizException(ResultCode.UNAUTHORIZED, "账号或口令不正确");
        }

        TokenPair pair = tokenIssuer.issueAndRemember(AuthConstants.END_ADMIN,
                principal.userId(), tenant.getId(), principal.getUsername());
        TenantContext.runAs(tenant.getId(), () -> userMapper.touchLastLogin(principal.userId()));
        log.info("后台登录成功 tenantId={} userId={} username={}", tenant.getId(), principal.userId(),
                principal.getUsername());
        return pair;
    }

    @Override
    public TokenPair refresh(String refreshToken) {
        return tokenIssuer.refresh(AuthConstants.END_ADMIN, refreshToken);
    }

    @Override
    public void logout(Caller currentAccess, String refreshToken) {
        tokenIssuer.logout(AuthConstants.END_ADMIN, currentAccess, refreshToken);
    }

    @Override
    public CurrentAccountView me(Caller current) {
        return new CurrentAccountView(current.subjectId(), current.username(), current.tenantId(),
                current.end(), permissionService.resolve(current.subjectId()));
    }
}
