package com.wherelee.cabinet.interfaces.mini.auth;

import com.wherelee.cabinet.application.auth.CustomerAuthService;
import com.wherelee.cabinet.application.auth.dto.CustomerLoginRequest;
import com.wherelee.cabinet.application.auth.dto.CustomerLoginView;
import com.wherelee.cabinet.application.auth.dto.CustomerView;
import com.wherelee.cabinet.common.annotation.OperationLog;
import com.wherelee.cabinet.common.api.R;
import com.wherelee.cabinet.infrastructure.security.VerifiedToken;
import com.wherelee.cabinet.interfaces.mini.auth.dto.MiniRefreshRequest;
import com.wherelee.cabinet.interfaces.mini.auth.dto.MiniTokenView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端（寄存客户）认证接口。
 *
 * <p>路径前缀 {@code /api/mini/auth/*} 与"端"名 mini 保留不变：它表示"客户端这一端"，
 * 不是换电语义；改名会连带影响安全链匹配、前端基址与已写的文档，收益为零。
 *
 * <p>业务接口用 {@code hasRole('CUSTOMER')} 而不是只写 authenticated()：客户被冻结时
 * {@code CustomerAuthoritiesResolver} 返回空权限，于是拿到 403 而不是继续放行。
 */
@Tag(name = "用户端-认证")
@RestController
@RequestMapping("/api/mini/auth")
public class MiniAuthController {

    private final CustomerAuthService customerAuthService;

    public MiniAuthController(CustomerAuthService customerAuthService) {
        this.customerAuthService = customerAuthService;
    }

    @Operation(summary = "客户端登录", description = "wx.login 的 code 换 openid 再换 token；首次注册需带运营商编码")
    @OperationLog(module = "auth", operation = "客户登录")
    @PostMapping("/login")
    public R<CustomerLoginView> login(@Valid @RequestBody CustomerLoginRequest request) {
        return R.ok(customerAuthService.login(request));
    }

    @Operation(summary = "刷新凭证")
    @PostMapping("/refresh")
    public R<MiniTokenView> refresh(@Valid @RequestBody MiniRefreshRequest request) {
        var pair = customerAuthService.refresh(request.refreshToken());
        // 刷新不再回传身份字段（前端登录时已有），也避免 newRegister=false 这种无意义值
        return R.ok(new MiniTokenView(pair.accessToken(), pair.refreshToken(), pair.tokenType(),
                pair.accessExpiresIn(), pair.refreshExpiresIn()));
    }

    @Operation(summary = "注销")
    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping("/logout")
    public R<Void> logout(@AuthenticationPrincipal VerifiedToken current,
                          @RequestBody(required = false) MiniRefreshRequest request) {
        customerAuthService.logout(current, request == null ? null : request.refreshToken());
        return R.ok();
    }

    @Operation(summary = "当前客户信息", description = "不回传 openId/unionId：那是服务端身份标识")
    @PreAuthorize("hasRole('CUSTOMER')")
    @GetMapping("/me")
    public R<CustomerView> me(@AuthenticationPrincipal VerifiedToken current) {
        return R.ok(customerAuthService.currentProfile(current));
    }
}
