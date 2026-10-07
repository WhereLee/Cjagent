package com.wherelee.cabinet.common.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.wherelee.cabinet.config.CabinetProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用 Logback 的 ListAppender 直接断言日志事件。
 *
 * <p>为什么不用响应头断言：X-Request-Cost 方案已废弃（响应在控制器返回时就 committed，
 * filter 后置写头会被容器丢弃，而 MockMvc 不提交响应所以断言会给出假绿）。
 * 访问日志的契约本来就在服务端，那就在服务端断言。
 */
class AccessLogFilterTest {

    private final CabinetProperties properties = new CabinetProperties();
    private final AccessLogFilter filter = new AccessLogFilter(properties);

    private Logger testedLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        testedLogger = (Logger) LoggerFactory.getLogger(AccessLogFilter.class);
        appender = new ListAppender<>();
        appender.start();
        testedLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        testedLogger.detachAppender(appender);
    }

    private List<ILoggingEvent> events() {
        return appender.list;
    }

    @Test
    @DisplayName("正常请求记 INFO，含方法/路径/状态/耗时")
    void logsNormalRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/system/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(1, events().size(), "一次请求应产生一条访问日志");
        ILoggingEvent event = events().get(0);
        assertEquals(Level.INFO, event.getLevel());
        String msg = event.getFormattedMessage();
        assertTrue(msg.contains("GET /api/system/health"), msg);
        assertTrue(msg.contains("status=200"), msg);
        assertTrue(msg.contains("cost="), msg);
    }

    @Test
    @DisplayName("超过阈值升级为 WARN 并标注慢请求")
    void warnsWhenSlow() throws Exception {
        properties.getAccessLog().setSlowThresholdMs(0); // 任何请求都算慢
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/x");

        filter.doFilter(request, response(), new MockFilterChain());

        assertEquals(1, events().size());
        assertEquals(Level.WARN, events().get(0).getLevel(), "慢请求必须走 warn，否则没法单独捞");
        assertTrue(events().get(0).getFormattedMessage().startsWith("慢请求"));
    }

    @Test
    @DisplayName("enabled=false 时完全不打日志（压测场景可关）")
    void silentWhenDisabled() throws Exception {
        properties.getAccessLog().setEnabled(false);

        filter.doFilter(new MockHttpServletRequest("GET", "/api/system/health"), response(), new MockFilterChain());

        assertTrue(events().isEmpty(), "关闭后不该产生访问日志");
    }

    @Test
    @DisplayName("下游抛异常时仍然记录（异常路径的耗时往往是问题现场）")
    void stillLogsWhenChainFails() throws Exception {
        MockFilterChain failing = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                throw new IllegalStateException("boom");
            }
        };

        try {
            filter.doFilter(new MockHttpServletRequest("GET", "/api/system/boom"), response(), failing);
        } catch (IllegalStateException expected) {
            // 异常继续上抛给 GlobalExceptionHandler
        }

        assertFalse(events().isEmpty(), "异常请求也要留下访问日志");
        assertTrue(events().get(0).getFormattedMessage().contains("cost="));
    }

    private static MockHttpServletResponse response() {
        return new MockHttpServletResponse();
    }
}
