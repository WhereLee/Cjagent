package com.wherelee.cabinet.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherelee.cabinet.common.api.R;
import com.wherelee.cabinet.common.api.ResultCode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 把认证/授权失败按统一响应体写出去。
 *
 * <p>为什么必须自己写：Spring Security 的失败处理发生在过滤器层，默认输出是空的 401/403
 * 加上一堆 {@code WWW-Authenticate} 头，前端拿到的结构与业务接口完全不一样，
 * 拦截器就只能靠 HTTP 状态码硬猜。统一成 {@code R} 之后，前端只有一条处理路径。
 *
 * <p>写在工具类而不是交给 {@code @RestControllerAdvice}：Security 的异常在过滤器链里抛出，
 * 走不到 ControllerAdvice，这一点很容易踩。
 */
public final class AuthFailureWriter {

    private AuthFailureWriter() {
    }

    /**
     * @param httpStatus 与业务码语义一致的 HTTP 状态（401 未认证 / 403 无权限）
     */
    public static void write(HttpServletResponse response,
                             ObjectMapper objectMapper,
                             int httpStatus,
                             ResultCode code,
                             String message) throws IOException {
        if (response.isCommitted()) {
            return; // 已经写出响应头就不再叠加，避免二次提交异常
        }
        response.setStatus(httpStatus);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(R.fail(code, message)));
    }
}
