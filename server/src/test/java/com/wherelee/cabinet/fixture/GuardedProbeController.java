package com.wherelee.cabinet.fixture;

import com.wherelee.cabinet.common.api.R;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/probe")
public class GuardedProbeController {

    @PreAuthorize("hasAuthority('probe:read')")
    @GetMapping("/permission")
    public R<String> needPermission() {
        return R.ok("granted");
    }

    @PreAuthorize("hasRole('OPS')")
    @GetMapping("/role")
    public R<String> needRole() {
        return R.ok("granted");
    }

    @GetMapping("/authenticated")
    public R<String> justAuthenticated() {
        return R.ok("granted");
    }
}
