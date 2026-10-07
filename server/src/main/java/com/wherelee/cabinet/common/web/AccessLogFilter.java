package com.wherelee.cabinet.common.web;

import com.wherelee.cabinet.config.CabinetProperties;
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

/**
 * 访问日志与慢请求告警：回答"这个接口耗了多久、是不是在变慢"。
 *
 * <p>只有 traceId 的话，日志能告诉你"哪一行出错了"，但告诉不了你"哪个接口慢了"。
 * 慢请求按 warn 单独记录，是无需上 APM 也能第一时间发现劣化的最低成本手段。
 *
 * <p><b>为什么不做成 X-Request-Cost 响应头</b>（实测踩到的坑）：Spring 写回 body 时会
 * flush 输出流，响应在控制器返回时已经 committed；此时 filter 在 {@code finally} 里
 * {@code setHeader} 会被 Servlet 容器**静默丢弃**。MockMvc 的 mock response 不会真正提交，
 * 所以单测里看着是绿的，真实 HTTP 上头根本不存在。
 * 客户端要的耗时另有两个可靠来源：服务端本日志（含 status）与 Nginx 的 {@code $request_time}；
 * 聚合统计用 actuator 自带的 {@code http.server.requests} 指标。
 *
 * <p>顺序在 {@code TraceIdFilter}（HIGHEST+10）之后，保证日志已带上 traceId。
 *
 * <p>耗时用 {@link System#nanoTime()} 而不是 currentTimeMillis：后者受系统时钟回拨/NTP 校正
 * 影响，可能算出负数或异常大的值。
 */
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
        // 在 TraceIdFilter(HIGHEST_PRECEDENCE + 10) 之后，让访问日志带上 traceId
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}
