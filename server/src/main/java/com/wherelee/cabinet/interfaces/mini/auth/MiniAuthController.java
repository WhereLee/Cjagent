package com.wherelee.cabinet.interfaces.mini.auth;

import com.wherelee.cabinet.application.auth.RiderAuthService;
import com.wherelee.cabinet.application.auth.dto.RiderLoginRequest;
import com.wherelee.cabinet.application.auth.dto.RiderLoginView;
import com.wherelee.cabinet.common.annotation.OperationLog;
import com.wherelee.cabinet.common.api.R;
import com.wherelee.cabinet.domain.entity.SysRider;
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
 * 骑手端认证接口。
 *
 * <p>业务接口用 {@code hasRole('RIDER')} 而不是只写 authenticated()：骑手被冻结时
 * {@code RiderAuthoritiesResolver} 返回空权限，于是拿到 403 而不是继续放行。
 */
@Tag(name = "骑手端-认证")
@RestController
@RequestMapping("/api/mini/auth")
public class MiniAuthController {

    private final RiderAuthService riderAuthService;

    public MiniAuthController(RiderAuthService riderAuthService) {
        this.riderAuthService = riderAuthService;
    }

    @Operation(summary = "小程序登录", description = "wx.login 的 code 换 openid 再换 token；首次注册需带租户编码")
    @OperationLog(module = "auth", operation = "骑手登录")
    @PostMapping("/login")
    public R<RiderLoginView> login(@Valid @RequestBody RiderLoginRequest request) {
        return R.ok(riderAuthService.login(request));
    }

    @Operation(summary = "刷新凭证")
    @PostMapping("/refresh")
    public R<MiniTokenView> refresh(@Valid @RequestBody MiniRefreshRequest request) {
        var pair = riderAuthService.refresh(request.refreshToken());
        // 刷新不再回传身份字段（前端登录时已有），也避免 newRegister=false 这种无意义值
        return R.ok(new MiniTokenView(pair.accessToken(), pair.refreshToken(), pair.tokenType(),
                pair.accessExpiresIn(), pair.refreshExpiresIn()));
    }

    @Operation(summary = "注销")
    @PreAuthorize("hasRole('RIDER')")
    @PostMapping("/logout")
    public R<Void> logout(@AuthenticationPrincipal VerifiedToken current,
                          @RequestBody(required = false) MiniRefreshRequest request) {
        riderAuthService.logout(current, request == null ? null : request.refreshToken());
        return R.ok();
    }

    @Operation(summary = "当前骑手信息")
    @PreAuthorize("hasRole('RIDER')")
    @GetMapping("/me")
    public R<SysRider> me(@AuthenticationPrincipal VerifiedToken current) {
        return R.ok(riderAuthService.currentRider(current));
    }
}
