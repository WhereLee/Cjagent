package com.wherelee.cabinet;

import com.fasterxml.jackson.databind.JsonNode;
import com.wherelee.cabinet.servicedesk.app.WorkOrderHandoffService;
import com.wherelee.cabinet.servicedesk.api.WorkOrderIntent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 交接事实：只装事实与建议，不替业务员做决定；派单时父单结论必须传下去。 */
@DisplayName("工单交接事实：诉求、尝试、用户位置、下一步、父单结论")
class WorkOrderHandoffServiceTest {

    private final WorkOrderHandoffService handoffs = new WorkOrderHandoffService();

    @Test
    @DisplayName("AI 转报生成结构化交接：用户原话、已尝试、是否在原地、建议下一步")
    void aiReportCarriesStructuredFacts() {
        String json = handoffs.aiReport("SO-1", "门打不开，东西在里面", "OPEN_TEMP 已下发无回执",
                true, WorkOrderIntent.DOOR_NOT_OPEN, "trace-9");
        JsonNode node = handoffs.read(json);

        assertEquals("SO-1", node.get("orderNo").asText());
        assertEquals("门打不开，东西在里面", node.get("userStatement").asText());
        assertEquals("OPEN_TEMP 已下发无回执", node.get("attempt").asText());
        assertTrue(node.get("userOnSite").asBoolean(), "用户明确说在原地就要记下来，不能丢");
        assertTrue(node.get("nextStep").asText().contains("柜前"),
                "门打不开的下一步应指向现场确认：" + node.get("nextStep"));
        assertEquals("trace-9", node.get("traceId").asText());
        assertTrue(node.get("parentResultNote").isNull(), "AI 单还没有父单结论");
    }

    @Test
    @DisplayName("派线下：父单的尝试与建议原样保留，并补上父单结论")
    void childHandoffKeepsParentFactsAndAddsConclusion() {
        String parent = handoffs.aiReport("SO-1", "门打不开", "OPEN_TEMP 无回执",
                false, WorkOrderIntent.DOOR_NOT_OPEN, "trace-9");
        String child = handoffs.forChild(parent, "已电话确认需到场取出");

        JsonNode node = handoffs.read(child);
        assertEquals("OPEN_TEMP 无回执", node.get("attempt").asText(), "子单必须看得到线上试过什么");
        assertEquals("已电话确认需到场取出", node.get("parentResultNote").asText());
        assertTrue(!node.get("userOnSite").asBoolean(), "用户不在原地这条事实也要传下去");
    }

    @Test
    @DisplayName("父单没有交接包或 JSON 写坏：仍要生成带父单结论的交接，不能挡住派单")
    void brokenParentHandoffStillYieldsConclusion() {
        assertEquals("父单结论", handoffs.read(handoffs.forChild(null, "父单结论"))
                .get("parentResultNote").asText());
        assertEquals("父单结论", handoffs.read(handoffs.forChild("{not-json", "父单结论"))
                .get("parentResultNote").asText());
    }
}
