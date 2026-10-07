package com.wherelee.cabinet.common.mask;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 脱敏行为。这类代码的风险是"看起来生效其实没生效"，所以每条都要断言到原文不可见。
 */
class MaskUtilsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    record RiderVo(Long id,
                   @JsonMask(MaskType.PHONE) String phone,
                   @JsonMask(MaskType.NAME) String realName,
                   String plainField) {
    }

    @Test
    @DisplayName("按字段名脱敏，嵌套对象与数组同样处理")
    void masksNestedStructures() {
        String summary = MaskUtils.toMaskedSummary(mapper, Map.of(
                "password", "SuperSecret!",
                "user", Map.of("phone", "13800138000", "note", "正常内容"),
                "orders", List.of(Map.of("idCard", "110101199001011234"))));

        assertFalse(summary.contains("SuperSecret"), "口令仍可见: " + summary);
        assertFalse(summary.contains("13800138000"), "手机号仍可见: " + summary);
        assertFalse(summary.contains("110101199001011234"), "身份证仍可见: " + summary);
        assertTrue(summary.contains("138****8000"), "手机号应按部分遮蔽保留: " + summary);
        assertTrue(summary.contains("正常内容"), "非敏感字段不该被误伤: " + summary);
    }

    @Test
    @DisplayName("字段名写法差异也要命中（大小写、下划线）")
    void toleratesFieldNameVariants() {
        String summary = MaskUtils.toMaskedSummary(mapper,
                Map.of("PASS_WORD", "abc12345", "Mobile", "13800138000"));
        assertFalse(summary.contains("abc12345"), summary);
        assertFalse(summary.contains("13800138000"), summary);
    }

    @Test
    @DisplayName("超长入参摘要要截断，避免审计表被大字段撑爆")
    void truncatesLongPayload() {
        String big = "x".repeat(5000);
        String summary = MaskUtils.toMaskedSummary(mapper, Map.of("blob", big));
        assertTrue(summary.length() < 2200, "摘要长度应被限制，实际 " + summary.length());
        assertTrue(summary.contains("truncated"), "截断要留下痕迹，否则会被误读成完整内容: " + summary);
    }

    @Test
    @DisplayName("@JsonMask 只管出参：VO 字段按类型遮蔽，其他字段不受影响")
    void jsonMaskAppliesPerField() throws Exception {
        String json = mapper.writeValueAsString(
                new RiderVo(1234567890123L, "13800138000", "张三丰", "plain-value"));

        assertTrue(json.contains("\"phone\":\"138****8000\""), json);
        assertTrue(json.contains("\"realName\":\"张**\""), json);
        assertTrue(json.contains("\"plainField\":\"plain-value\""), "未标注字段不该被遮: " + json);
    }

    @Test
    @DisplayName("防过度脱敏：tenantCode 不是微信 code，必须原样保留")
    void doesNotOverMaskBusinessFields() {
        // 曾经 "包含 code" 把 tenantCode 也遮了，登录审计就看不出针对哪个租户
        String summary = MaskUtils.toMaskedSummary(mapper,
                Map.of("tenantCode", "t-one", "code", "wx-code-123"));
        assertTrue(summary.contains("t-one"), "tenantCode 被误遮，日志失去价值: " + summary);
        assertFalse(summary.contains("wx-code-123"), "微信 code 必须遮: " + summary);
    }

    @Test
    @DisplayName("长度不足时全遮，不原样吐出")
    void shortValuesAreFullyMasked() {
        // 位数不够时保留前 3 后 4 会泄露全部位，所以只能是全遮
        assertEquals("*****", MaskType.PHONE.mask("12345"));
        assertEquals("****", MaskType.BANK_CARD.mask("1234"));
        assertEquals("", MaskType.NAME.mask(""));
        assertEquals("张**", MaskType.NAME.mask("张三丰"));
    }
}
