package com.wherelee.cabinet.platform.web;

import com.wherelee.cabinet.app.CabinetProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class AccessLogFilter extends OncePerRequestFilter implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(AccessLogFilter.class);

    private final CabinetProperties properties;

    public AccessLogFilter(CabinetProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            CabinetProperties.AccessLog cfg = properties.getAccessLog();
            long costMs = (System.nanoTime() - start) / 1_000_000;
            if (!cfg.isEnabled()) {
                return;
            }
            String line = request.getMethod() + " " + request.getRequestURI()
                    + " status=" + response.getStatus() + " cost=" + costMs + "ms";
            if (costMs >= cfg.getSlowThresholdMs()) {
                log.warn("慢请求 {}", line);
            } else {
                log.info("{}", line);
            }
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}
