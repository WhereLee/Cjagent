package com.wherelee.cabinet.common.mask;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

public final class MaskUtils {

    private static final Map<String, MaskType> CONTAINS_RULES = Map.ofEntries(
            Map.entry("password", MaskType.ALL),
            Map.entry("passwd", MaskType.ALL),
            Map.entry("secret", MaskType.ALL),
            Map.entry("token", MaskType.ALL),
            Map.entry("credential", MaskType.ALL));

    private static final Map<String, MaskType> EXACT_RULES = Map.ofEntries(
            Map.entry("code", MaskType.ALL),
            Map.entry("phone", MaskType.PHONE),
            Map.entry("mobile", MaskType.PHONE),
            Map.entry("idcard", MaskType.ID_CARD),
            Map.entry("idno", MaskType.ID_CARD),
            Map.entry("email", MaskType.EMAIL),
            Map.entry("nickname", MaskType.NAME),
            Map.entry("realname", MaskType.NAME),
            Map.entry("address", MaskType.ADDRESS),
            Map.entry("bankcard", MaskType.BANK_CARD));

    public static final int MAX_PARAM_LENGTH = 2000;

    private MaskUtils() {
    }

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
        String normalized = fieldName.toLowerCase(Locale.ROOT).replace("_", "");
        for (Map.Entry<String, MaskType> rule : CONTAINS_RULES.entrySet()) {
            if (normalized.contains(rule.getKey())) {
                return rule.getValue();
            }
        }
        return EXACT_RULES.get(normalized);
    }
}
