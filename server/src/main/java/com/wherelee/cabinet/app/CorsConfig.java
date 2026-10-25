package com.wherelee.cabinet.app;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties(CabinetProperties.class)
public class CorsConfig implements WebMvcConfigurer {

    private final CabinetProperties properties;

    public CorsConfig(CabinetProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        var cors = properties.getCors();
        if (cors.getAllowedOriginPatterns().isEmpty()) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOriginPatterns(cors.getAllowedOriginPatterns().toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders(cors.getExposedHeaders().toArray(String[]::new))
                .allowCredentials(true)
                .maxAge(cors.getMaxAge());
    }
}
