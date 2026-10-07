package com.wherelee.cabinet.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 直接跑 JacksonConfig 里的 customizer，不启动 Spring 上下文。
 *
 * <p>为什么要单独一个不依赖容器的测试：这两条约定（Long 转字符串、时间格式）一旦回归，
 * 坏的是所有接口的返回结构；用纯单测守住它，秒级就能发现，不用等集成测试起库。
 */
class JacksonConfigTest {

    record Payload(Long id, Long primitiveLike, LocalDateTime createTime, LocalDate bizDate) {
    }

    private ObjectMapper mapper() {
        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
        new JacksonConfig().cabinetJacksonCustomizer().customize(builder);
        return builder.build();
    }

    @Test
    @DisplayName("Long 输出为字符串（雪花 ID 超出 JS 安全整数）")
    void longSerializesAsString() throws Exception {
        String json = mapper().writeValueAsString(new Payload(1948123456789012345L, 1L, null, null));

        assertTrue(json.contains("\"id\":\"1948123456789012345\""), json);
        assertTrue(json.contains("\"primitiveLike\":\"1\""), "基本类型 long 也要覆盖: " + json);
    }

    @Test
    @DisplayName("时间按 yyyy-MM-dd HH:mm:ss / yyyy-MM-dd 输出")
    void dateTimeFormatted() throws Exception {
        String json = mapper().writeValueAsString(
                new Payload(1L, 2L, LocalDateTime.of(2026, 10, 7, 17, 18, 53), LocalDate.of(2026, 10, 7)));

        assertTrue(json.contains("\"createTime\":\"2026-10-07 17:18:53\""), json);
        assertTrue(json.contains("\"bizDate\":\"2026-10-07\""), json);
    }

    @Test
    @DisplayName("同一格式可读回来，且字符串 id 能反序列化成 Long")
    void readsBackOwnFormat() throws Exception {
        String json = "{\"id\":\"1948123456789012345\",\"primitiveLike\":2,"
                + "\"createTime\":\"2026-10-07 17:18:53\",\"bizDate\":\"2026-10-07\"}";

        Payload parsed = mapper().readValue(json, Payload.class);

        assertEquals(1948123456789012345L, parsed.id().longValue());
        assertEquals(LocalDateTime.of(2026, 10, 7, 17, 18, 53), parsed.createTime());
        assertEquals(LocalDate.of(2026, 10, 7), parsed.bizDate());
    }
}
