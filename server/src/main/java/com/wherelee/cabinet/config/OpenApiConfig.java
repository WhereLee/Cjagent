package com.wherelee.cabinet.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 接口文档：按端分组，避免用户端同学翻后台接口。
 *
 * <p>生产环境要通过 {@code springdoc.api-docs.enabled=false} 与
 * {@code cabinet.openapi.enabled=false} 关掉，不能把接口清单暴露到公网。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI cabinetOpenApi() {
        return new OpenAPI().info(new Info()
                .title("暂存柜 SaaS 接口文档")
                .version("0.0.1-SNAPSHOT")
                .description("底座阶段仅提供系统自检接口；业务接口随阶段 1 逐步补充")
                .license(new License().name("Private")));
    }

    @Bean
    public GroupedOpenApi systemApi() {
        return GroupedOpenApi.builder().group("0-system").pathsToMatch("/api/system/**").build();
    }

    @Bean
    public GroupedOpenApi adminApi() {
        return GroupedOpenApi.builder().group("1-admin").pathsToMatch("/api/admin/**").build();
    }

    @Bean
    public GroupedOpenApi miniApi() {
        return GroupedOpenApi.builder().group("2-mini").pathsToMatch("/api/mini/**").build();
    }
}
