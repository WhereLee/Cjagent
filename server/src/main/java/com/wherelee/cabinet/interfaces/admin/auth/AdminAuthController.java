package com.wherelee.cabinet.interfaces.admin.auth;

import com.wherelee.cabinet.application.auth.AdminAuthService;
import com.wherelee.cabinet.application.auth.dto.AdminLoginRequest;
import com.wherelee.cabinet.application.auth.dto.CurrentAccountView;
import com.wherelee.cabinet.common.annotation.OperationLog;
import com.wherelee.cabinet.common.api.R;
import com.wherelee.cabinet.infrastructure.security.TokenPair;
import com.wherelee.cabinet.infrastructure.security.VerifiedToken;
import com.wherelee.cabinet.interfaces.admin.auth.dto.RefreshRequest;
import com.wherelee.cabinet.interfaces.admin.auth.dto.TokenView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台认证接口：登录、刷新、注销、当前账号。
 *
 * <p>Controller 只做参数校验与转换，业务在 application 层——两端接口形态将来会分化
 * （后台多一套权限与租户管理），薄一层容易保持边界。
 */
@Tag(name = "后台-认证")
@RestController
@RequestMapping("/api/admin/auth")
public class AdminAuthController {

    private final AdminAuthService authService;

    public AdminAuthController(AdminAuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "账号密码登录", description = "需带租户编码：用户名只租户内唯一")
    @OperationLog(module = "auth", operation = "后台登录")
    @PostMapping("/login")
    public R<TokenView> login(@Valid @RequestBody AdminLoginRequest request) {
        return R.ok(toView(authService.login(request)));
    }

    @Operation(summary = "刷新凭证", description = "旧 refresh 用过即废（一次性），同时作废旧 access 不需要")
    @PostMapping("/refresh")
    public R<TokenView> refresh(@Valid @RequestBody RefreshRequest request) {
        return R.ok(toView(authService.refresh(request.refreshToken())));
    }

    @Operation(summary = "注销", description = "当前 access 进黑名单，可选带上 refresh 一并作废")
    @PostMapping("/logout")
    public R<Void> logout(@AuthenticationPrincipal VerifiedToken current,
                          @RequestBody(required = false) RefreshRequest request) {
        authService.logout(current, request == null ? null : request.refreshToken());
        return R.ok();
    }

    @Operation(summary = "当前账号与权限", description = "前端启动时拉一次用于渲染菜单")
    @GetMapping("/me")
    public R<CurrentAccountView> me(@AuthenticationPrincipal VerifiedToken current) {
        return R.ok(authService.me(current));
    }

    private static TokenView toView(TokenPair pair) {
        return new TokenView(pair.accessToken(), pair.refreshToken(), pair.tokenType(),
                pair.accessExpiresIn(), pair.refreshExpiresIn());
    }
}
