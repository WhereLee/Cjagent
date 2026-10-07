package com.wherelee.cabinet.fixture;

import com.wherelee.cabinet.common.api.R;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 测试专用探针接口：用来验证 {@code @PreAuthorize} 的权限判定真的生效。
 *
 * <p>为什么放在 src/test：它是<b>验证手段</b>不是业务能力，不该出现在生产构件里。
 * 底座阶段还没有任何需要鉴权的业务接口，没有这个探针，"有 token 但权限不足要 403"
 * 这条断言就只能靠猜。
 */
@RestController
@RequestMapping("/api/admin/probe")
public class GuardedProbeController {

    /** 需要权限点 {@code probe:read}，与"已登录"是两件事。 */
    @PreAuthorize("hasAuthority('probe:read')")
    @GetMapping("/permission")
    public R<String> needPermission() {
        return R.ok("granted");
    }

    /** 需要角色 OPS，验证 ROLE_ 前缀的装载是否正确。 */
    @PreAuthorize("hasRole('OPS')")
    @GetMapping("/role")
    public R<String> needRole() {
        return R.ok("granted");
    }

    /** 只要求登录，作为对照组。 */
    @GetMapping("/authenticated")
    public R<String> justAuthenticated() {
        return R.ok("granted");
    }
}
