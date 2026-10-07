package com.wherelee.cabinet.common.trace;

import com.wherelee.cabinet.common.api.R;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * 链路追踪：为每个请求建立 traceId，写入 MDC 并回写响应头。
 *
 * <p>上游（网关/前端）若已带 {@code X-Trace-Id} 且格式合法则沿用，否则新生成，
 * 保证跨服务日志能串起来；对入参做白名单校验，避免日志注入（换行、超长等）。
 */
@Component
public class TraceIdFilter extends OncePerRequestFilter implements Ordered {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    /** 只允许 8-64 位字母数字与 - _，防止把控制字符写进日志。 */
    private static final Pattern VALID_TRACE_ID = Pattern.compile("^[0-9A-Za-z_-]{8,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(TRACE_ID_HEADER);
        String traceId = (StringUtils.hasText(incoming) && VALID_TRACE_ID.matcher(incoming).matches())
                ? incoming
                : generate();
        try {
            MDC.put(R.TRACE_ID_KEY, traceId);
            response.setHeader(TRACE_ID_HEADER, traceId);
            chain.doFilter(request, response);
        } finally {
            // 线程池复用，必须清理，否则下一个请求会读到上一个 traceId
            MDC.remove(R.TRACE_ID_KEY);
        }
    }

    @Override
    public int getOrder() {
        // 尽量靠前，保证后续过滤器/拦截器的日志都带上 traceId
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    private String generate() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }
}
