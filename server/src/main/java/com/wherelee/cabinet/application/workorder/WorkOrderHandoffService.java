package com.wherelee.cabinet.application.workorder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wherelee.cabinet.domain.enums.WorkOrderIntent;
import org.springframework.stereotype.Component;

/**
 * 交接事实：把“用户说了什么、试过什么、人在不在、下一步该做什么”装进一个 JSON。
 *
 * <p>为什么结构化而不是一段备注：线下运维接手时最想知道的三件事分别是“用户到底遇到什么”、
 * “线上已经试过什么（避免重复摇设备）”、“用户还在不在柜前（决定要不要马上出门）”。
 * 写成自由文本，第三个人接手就要重新问一遍。
 *
 * <p>JSON 只存不查（列表查询不解析它），所以字段变更不需要迁移；展示端按字段读。
 */
@Component
public class WorkOrderHandoffService {

    private final ObjectMapper mapper = new ObjectMapper();

    /** AI 转报时生成交接包；userOnSite 未知时写 null，不猜。 */
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

    /**
     * 派线下时给子单的交接包：复制父单已有事实，并补上“父单结论”。
     *
     * <p>父单没有交接包（设备巡检自动开的单）也要生成一个只带父单结论的 JSON：
     * 子单详情至少要说清“为什么派我出来”。
     */
    public String forChild(String parentHandoffJson, String parentResultNote) {
        ObjectNode node = mapper.createObjectNode();
        if (parentHandoffJson != null && !parentHandoffJson.isBlank()) {
            try {
                JsonNode parsed = mapper.readTree(parentHandoffJson);
                if (parsed.isObject()) {
                    node = (ObjectNode) parsed;
                }
            } catch (Exception ignored) {
                // 历史/手工写坏的 JSON 不让它挡住派单：退回只带父单结论的交接包
            }
        }
        if (parentResultNote != null && !parentResultNote.isBlank()) {
            node.put("parentResultNote", parentResultNote);
        }
        return write(node);
    }

    /** 按现象给一条可执行的下一步；没有更好判断时要求先联系用户确认。 */
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
            // ObjectNode 序列化不会失败；真失败说明代码问题，直接抛而不是静默丢交接事实
            throw new IllegalStateException("交接事实序列化失败", e);
        }
    }

    /** 供展示层/测试读取：字段名统一小驼峰，不做下划线转换。 */
    public JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }
}
