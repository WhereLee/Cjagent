package com.wherelee.cabinet.bff.admin.user;

import com.wherelee.cabinet.identity.api.UserQueryPort;
import com.wherelee.cabinet.identity.api.UserView;
import com.wherelee.cabinet.common.api.PageQuery;
import com.wherelee.cabinet.common.api.PageResult;
import com.wherelee.cabinet.common.api.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "后台-账号")
@RestController
@RequestMapping("/api/admin/users")
@Validated
public class AdminUserQueryController {

    private final UserQueryPort userQueryService;

    public AdminUserQueryController(UserQueryPort userQueryService) {
        this.userQueryService = userQueryService;
    }

    @Operation(summary = "账号分页列表", description = "租户隔离由拦截器自动生效；手机号出参脱敏")
    @PreAuthorize("hasAuthority('system:user:list')")
    @GetMapping
    public R<PageResult<UserView>> list(
            @RequestParam(required = false) @Size(max = 64, message = "keyword 过长") String keyword,
            @RequestParam(required = false) @Min(0) @Max(1) Integer status,
            @Valid PageQuery pageQuery) {
        return R.ok(userQueryService.page(keyword, status, pageQuery));
    }
}
