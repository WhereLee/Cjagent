package com.wherelee.cabinet.interfaces.admin.user;

import com.wherelee.cabinet.application.user.UserQueryService;
import com.wherelee.cabinet.application.user.dto.UserView;
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

/**
 * 后台账号查询接口。
 *
 * <p>它是底座里<b>第一个受权限保护的接口</b>，存在的意义不是"提供功能"，而是把三件事
 * 一次跑通并被前端消费：分页入参约定、{@code hasAuthority} 权限判定、{@code @JsonMask} 出参脱敏。
 *
 * <p>查询接口<b>不加 {@code @OperationLog}</b>：只读请求进审计会让表迅速被翻页请求灌满，
 * 审计噪音一高，真正该看的写操作记录就没人看了。写操作才留痕。
 */
@Tag(name = "后台-账号")
@RestController
@RequestMapping("/api/admin/users")
@Validated
public class AdminUserQueryController {

    private final UserQueryService userQueryService;

    public AdminUserQueryController(UserQueryService userQueryService) {
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
