package com.wherelee.cabinet.platform.web;

import com.wherelee.cabinet.common.api.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "系统自检")
@RestController
@RequestMapping("/api/system")
public class SystemHealthController {

    private final HealthEndpoint healthEndpoint;

    public SystemHealthController(HealthEndpoint healthEndpoint) {
        this.healthEndpoint = healthEndpoint;
    }

    @Operation(summary = "组件连通性自检", description = "返回 MySQL / Redis / RocketMQ 各项状态")
    @GetMapping("/health")
    public R<HealthComponent> health() {
        return R.ok(healthEndpoint.health());
    }
}
