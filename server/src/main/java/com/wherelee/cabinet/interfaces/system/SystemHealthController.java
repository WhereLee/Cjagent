package com.wherelee.cabinet.interfaces.system;

import com.wherelee.cabinet.common.api.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统自检接口：把 actuator 的 health 用统一响应体包一层，给前端和运维脚本一个稳定入口。
 *
 * <p>actuator 原始路径 {@code /actuator/health} 同时保留，二者用途区分：
 * <ul>
 *   <li>容器/探针用 actuator 端点（含 liveness / readiness 分组）</li>
 *   <li>人和前端用本接口（结构统一，带 traceId）</li>
 * </ul>
 */
@Tag(name = "系统自检")
@RestController
@RequestMapping("/api/system")
public class SystemHealthController {

    private final HealthEndpoint healthEndpoint;

    public SystemHealthController(HealthEndpoint healthEndpoint) {
        this.healthEndpoint = healthEndpoint;
    }

    @Operation(summary = "组件连通性自检", description = "返回 MySQL / PG / Redis / RocketMQ 各项状态")
    @GetMapping("/health")
    public R<HealthComponent> health() {
        return R.ok(healthEndpoint.health());
    }
}
