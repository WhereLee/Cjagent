package com.wherelee.cabinet.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 从当前线程拿请求对象与来源 IP（切面里用，Controller 请走参数注入）。
 *
 * <p>IP 取值的取舍：{@code X-Forwarded-For} 第一个地址是客户端 IP，但它<b>可以被客户端伪造</b>。
 * 因此这里只在前面有可信代理的前提下使用：
 * <ul>
 *   <li>本地直连（dev/测试）时读到的就是 remoteAddr，不受影响；</li>
 *   <li>生产由 Nginx 反代统一覆写 XFF，取第一段的值即可信；</li>
 *   <li>所以<b>不要用这个值做安全判定</b>（如 IP 白名单），只用它做限流维度与日志展示——
 *       需要严格来源识别时应由代理层落地。</li>
 * </ul>
 */
public final class WebUtils {

    private static final String UNKNOWN = "unknown";

    private WebUtils() {
    }

    /** 当前请求；非 Web 线程（定时任务、MQ 消费）返回 null，调用方必须能处理 null。 */
    public static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }

    public static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded) && !UNKNOWN.equalsIgnoreCase(forwarded)) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        String real = request.getHeader("X-Real-IP");
        return StringUtils.hasText(real) ? real.trim() : request.getRemoteAddr();
    }
}
