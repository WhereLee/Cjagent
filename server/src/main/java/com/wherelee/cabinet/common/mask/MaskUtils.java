package com.wherelee.cabinet.common.mask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * 字符串/对象层面的脱敏：给审计日志与入参摘要用。
 *
 * <p>{@link JsonMask} 管的是"接口出参"，而审计要写的是"入参原文"——两者不能共用一条路：
 * 入参对象上没有出参注解，必须按<b>字段名</b>识别敏感项。
 *
 * <p>按字段名匹配是有意的保守策略：宁可多遮一些，也不要漏掉一个密码。
 */
public final class MaskUtils {

    /**
     * 可以用"包含"匹配的高置信敏感词：这些词出现在任何字段名里都当敏感处理。
     *
     * <p>注意不能把 {@code code} 放进去："包含"匹配会把 {@code tenantCode} 误判成微信 code
     * 而遮住，审计就看不出一个登录尝试针对的是哪个租户——脱敏过度会直接让日志失去价值。
     */
    private static final Map<String, MaskType> CONTAINS_RULES = Map.ofEntries(
            Map.entry("password", MaskType.ALL),
            Map.entry("passwd", MaskType.ALL),
            Map.entry("secret", MaskType.ALL),
            Map.entry("token", MaskType.ALL),
            Map.entry("credential", MaskType.ALL));

    /** 只在字段名完全相等时才遮的敏感词（易误伤或业务上有同名非敏感字段）。 */
    private static final Map<String, MaskType> EXACT_RULES = Map.ofEntries(
            Map.entry("code", MaskType.ALL),          // wx.login 的 code，不能包含匹配
            Map.entry("phone", MaskType.PHONE),
            Map.entry("mobile", MaskType.PHONE),
            Map.entry("idcard", MaskType.ID_CARD),
            Map.entry("idno", MaskType.ID_CARD),
            Map.entry("email", MaskType.EMAIL),
            Map.entry("nickname", MaskType.NAME),
            Map.entry("realname", MaskType.NAME),
            Map.entry("address", MaskType.ADDRESS),
            Map.entry("bankcard", MaskType.BANK_CARD));

    /** 审计表里 params 是 TEXT，但一条 SQL 塞进 1MB 字符串没有意义，先截断。 */
    public static final int MAX_PARAM_LENGTH = 2000;

    private MaskUtils() {
    }

    /**
     * 把请求入参转成"已脱敏 + 已截断"的摘要字符串。
     *
     * <p>序列化失败不能让审计丢记录，所以兜底成一段说明文本（包异常类型）。
     */
    public static String toMaskedSummary(ObjectMapper objectMapper, Object body) {
        if (body == null) {
            return null;
        }
        String json;
        try {
            JsonNode node = objectMapper.valueToTree(body);
            maskNode(node);
            json = objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            json = "{\"_serializeError\":\"" + e.getClass().getSimpleName() + "\"}";
        }
        return json.length() <= MAX_PARAM_LENGTH
                ? json
                : json.substring(0, MAX_PARAM_LENGTH) + "...(truncated, total=" + json.length() + ")";
    }

    /** 就地遮蔽 JSON 树里的敏感字段（含嵌套对象与数组）。 */
    private static void maskNode(JsonNode node) {
        if (node == null) {
            return;
        }
        if (node instanceof ObjectNode objectNode) {
            Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                MaskType type = sensitiveType(entry.getKey());
                JsonNode value = entry.getValue();
                if (type != null && value.isValueNode()) {
                    objectNode.put(entry.getKey(), type.mask(value.asText()));
                } else {
                    maskNode(value);
                }
            }
        } else if (node instanceof ArrayNode arrayNode) {
            arrayNode.forEach(MaskUtils::maskNode);
        }
    }

    private static MaskType sensitiveType(String fieldName) {
        if (fieldName == null) {
            return null;
        }
        // 容忍 password / Password / password_hash 这类写法差异
        String normalized = fieldName.toLowerCase(Locale.ROOT).replace("_", "");
        for (Map.Entry<String, MaskType> rule : CONTAINS_RULES.entrySet()) {
            if (normalized.contains(rule.getKey())) {
                return rule.getValue();
            }
        }
        return EXACT_RULES.get(normalized);
    }
}
