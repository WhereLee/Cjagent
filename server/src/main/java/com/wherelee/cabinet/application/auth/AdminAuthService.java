package com.wherelee.cabinet.application.auth;

import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.common.security.AuthConstants;
import com.wherelee.cabinet.common.security.LoginUser;
import com.wherelee.cabinet.domain.entity.SysTenant;
import com.wherelee.cabinet.infrastructure.mapper.SysUserMapper;
import com.wherelee.cabinet.infrastructure.security.AdminPermissionService;
import com.wherelee.cabinet.infrastructure.security.TokenIssuer;
import com.wherelee.cabinet.infrastructure.security.TokenPair;
import com.wherelee.cabinet.infrastructure.security.VerifiedToken;
import com.wherelee.cabinet.interfaces.admin.auth.dto.AdminLoginRequest;
import com.wherelee.cabinet.interfaces.admin.auth.dto.CurrentAccountView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;

/**
 * 后台登录编排：租户可用 → 口令认证 → 签发凭证。
 *
 * <p>刷新/注销不在这里重复实现，统一委托给 {@link TokenIssuer}（两端共用）。
 *
 * <p>三点安全约定：
 * <ul>
 *   <li><b>认证整段包在租户上下文里</b>：sys_user 的用户名只租户内唯一，
 *       没有上下文时要么查不到、要么撞进别的租户的账号。</li>
 *   <li><b>账号不存在与口令错误响应完全一致</b>（都是 40100），否则接口变成账号枚举器。</li>
 *   <li><b>租户停用/过期直接拒绝登录</b>：这是 SaaS 的付费边界，
 *       不是"能登录但看不到数据"那种半吊子状态。</li>
 * </ul>
 */
@Service
public class AdminAuthService {

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

    public TokenPair login(AdminLoginRequest request) {
        SysTenant tenant = tenantGuard.requireUsableByCode(request.tenantCode());

        LoginUser principal;
        try {
            principal = (LoginUser) TenantContext.callAs(tenant.getId(), () ->
                    authenticationManager.authenticate(
                                    new UsernamePasswordAuthenticationToken(request.username(), request.password()))
                            .getPrincipal());
        } catch (AuthenticationException e) {
            // BadCredentials / UserNotFound / Disabled 对外同一句话，原因只进日志
            log.warn("后台登录失败 tenant={} username={} 原因={}",
                    request.tenantCode(), request.username(), e.getClass().getSimpleName());
            throw new BizException(ResultCode.UNAUTHORIZED, "账号或口令不正确");
        }

        TokenPair pair = tokenIssuer.issueAndRemember(AuthConstants.END_ADMIN,
                principal.userId(), tenant.getId(), principal.getUsername());
        // sys_user 不是白名单表，任何读写都必须在租户上下文里做（包括回写登录时间）。
        // 这一行曾经写在校外，直接让登录因租户守卫拒绝而 500。
        TenantContext.runAs(tenant.getId(), () -> userMapper.touchLastLogin(principal.userId()));
        log.info("后台登录成功 tenantId={} userId={} username={}", tenant.getId(), principal.userId(),
                principal.getUsername());
        return pair;
    }

    public TokenPair refresh(String refreshToken) {
        return tokenIssuer.refresh(AuthConstants.END_ADMIN, refreshToken);
    }

    public void logout(VerifiedToken currentAccess, String refreshToken) {
        tokenIssuer.logout(AuthConstants.END_ADMIN, currentAccess, refreshToken);
    }

    /** 当前账号信息（前端启动时拉菜单用）。权限走缓存，所以这个方法很轻。 */
    public CurrentAccountView me(VerifiedToken current) {
        return new CurrentAccountView(current.subjectId(), current.username(), current.tenantId(),
                current.end(), permissionService.resolve(current.subjectId()));
    }
}
