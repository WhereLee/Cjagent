package com.wherelee.cabinet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 暂存柜 SaaS 启动类。
 *
 * <p>代码结构按“服务边界”组织（微服务化重构进行中）：
 * <ul>
 *   <li>{@code common}：纯契约（响应体、分页、错误码、注解、租户上下文、脱敏），不依赖框架与业务；</li>
 *   <li>{@code platform}：运行时平台（安全/租户/Web/MQ/审计/限流/幂等），相当于进程内网关；</li>
 *   <li>{@code identity/locker/storage/funds/device/servicedesk/notify/assistant}：八个业务服务，
 *       每个服务内部再分 api / app / domain / infra，跨服务只经 api 端口；</li>
 *   <li>{@code bff.admin / bff.mini}：两端 HTTP 入口与聚合（迁移中）；</li>
 *   <li>{@code app}：启动装配（数据源、MyBatis、Security、Jackson）。</li>
 * </ul>
 * 当前处于 P1：平台层已抽出，业务包仍按旧的 application/domain/infrastructure 分层，
 * 由 {@code ModuleBoundaryTest} 守住“只减不增”。
 */
@SpringBootApplication
public class CabinetServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(CabinetServerApplication.class, args);
    }
}
