package com.wherelee.cabinet.platform.web;

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

@Component
public class TraceIdFilter extends OncePerRequestFilter implements Ordered {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";

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
            MDC.remove(R.TRACE_ID_KEY);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    private String generate() {
        return java.util.UUID.randomUUID().toString().replace("-", "");
    }
}
