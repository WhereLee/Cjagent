package com.wherelee.cabinet.bff.mini.auth;

import com.wherelee.cabinet.identity.api.CustomerAuthPort;
import com.wherelee.cabinet.identity.api.CustomerLoginRequest;
import com.wherelee.cabinet.identity.api.CustomerLoginView;
import com.wherelee.cabinet.identity.api.CustomerView;
import com.wherelee.cabinet.common.annotation.OperationLog;
import com.wherelee.cabinet.common.api.R;
import com.wherelee.cabinet.platform.security.Caller;
import com.wherelee.cabinet.bff.mini.auth.dto.MiniRefreshRequest;
import com.wherelee.cabinet.bff.mini.auth.dto.MiniTokenView;
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

@Tag(name = "用户端-认证")
@RestController
@RequestMapping("/api/mini/auth")
public class MiniAuthController {

    private final CustomerAuthPort customerAuthService;

    public MiniAuthController(CustomerAuthPort customerAuthService) {
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
        return R.ok(new MiniTokenView(pair.accessToken(), pair.refreshToken(), pair.tokenType(),
                pair.accessExpiresIn(), pair.refreshExpiresIn()));
    }

    @Operation(summary = "注销")
    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping("/logout")
    public R<Void> logout(@AuthenticationPrincipal Caller current,
                          @RequestBody(required = false) MiniRefreshRequest request) {
        customerAuthService.logout(current, request == null ? null : request.refreshToken());
        return R.ok();
    }

    @Operation(summary = "当前客户信息", description = "不回传 openId/unionId：那是服务端身份标识")
    @PreAuthorize("hasRole('CUSTOMER')")
    @GetMapping("/me")
    public R<CustomerView> me(@AuthenticationPrincipal Caller current) {
        return R.ok(customerAuthService.currentProfile(current));
    }
}
