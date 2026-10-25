package com.wherelee.cabinet.servicedesk.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wherelee.cabinet.servicedesk.api.WorkOrderIntent;
import org.springframework.stereotype.Component;

@Component
public class WorkOrderHandoffService implements com.wherelee.cabinet.servicedesk.api.WorkOrderHandoffPort {

    private final ObjectMapper mapper = new ObjectMapper();

    public String aiReport(String orderNo, String userStatement, String attempt, Boolean userOnSite,
                           WorkOrderIntent intent, String traceId) {
        ObjectNode node = mapper.createObjectNode();
        node.put("orderNo", orderNo);
        node.put("userStatement", userStatement == null ? "" : userStatement);
        node.put("attempt", attempt == null || attempt.isBlank() ? "未记录" : attempt);
        if (userOnSite == null) {
            node.putNull("userOnSite");
        } else {
            node.put("userOnSite", userOnSite);
        }
        node.put("nextStep", nextStepOf(intent));
        node.putNull("parentResultNote");
        node.put("traceId", traceId);
        return write(node);
    }

    public String forChild(String parentHandoffJson, String parentResultNote) {
        ObjectNode node = mapper.createObjectNode();
        if (parentHandoffJson != null && !parentHandoffJson.isBlank()) {
            try {
                JsonNode parsed = mapper.readTree(parentHandoffJson);
                if (parsed.isObject()) {
                    node = (ObjectNode) parsed;
                }
            } catch (Exception ignored) {
            }
        }
        if (parentResultNote != null && !parentResultNote.isBlank()) {
            node.put("parentResultNote", parentResultNote);
        }
        return write(node);
    }

    static String nextStepOf(WorkOrderIntent intent) {
        if (intent == null) {
            return "联系用户确认现场情况后再分拣";
        }
        return switch (intent) {
            case DOOR_NOT_OPEN -> "远程开门未成功：到柜前确认柜门是否弹开、门磁是否复位";
            case DOOR_NOT_CLOSE -> "确认柜门是否被物品挡住，必要时到现场关门";
            case ITEM_MISSING -> "核对监控与物检记录，联系用户确认物品特征后再判定";
            case ITEM_LEFT -> "确认遗留物归属并安排取回";
            case DEVICE_OFFLINE -> "检查柜机供电与网络，恢复在线后重试";
            case BILLING_DISPUTE -> "核对计费快照与流水，给出费用说明";
            case PICKUP_CODE -> "核对取件码与订单状态，必要时重置取件码";
            case OTHER -> "联系用户确认现场情况后再分拣";
        };
    }

    private String write(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("交接事实序列化失败", e);
        }
    }

    public JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }
}
