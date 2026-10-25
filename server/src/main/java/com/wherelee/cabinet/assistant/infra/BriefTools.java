package com.wherelee.cabinet.assistant.infra;

import com.wherelee.cabinet.servicedesk.api.WorkOrderConsolePort;
import org.springframework.ai.tool.annotation.Tool;

import java.util.List;

public class BriefTools {

    private final WorkOrderConsolePort workOrders;
    private final Long workOrderId;

    public BriefTools(WorkOrderConsolePort workOrders, Long workOrderId) {
        this.workOrders = workOrders;
        this.workOrderId = workOrderId;
    }

    @Tool(description = "查工单本体：类型、状态、紧急度、所属队列、格口/柜机/点位、标题、开单时的检测值、处理时限。不含客户电话（电话不进 AI）。")
    public String workOrderContext() {
        WorkOrderConsolePort.WorkOrderView v = workOrders.one(workOrderId);
        return "type=" + v.type() + "；status=" + v.status() + "；urgent=" + (v.urgent() ? "紧急" : "普通")
                + "；queueSide=" + v.queueSide()
                + "；slotNo=" + nvl(v.slotNo()) + "；cabinetNo=" + nvl(v.cabinetNo())
                + "；site=" + nvl(v.siteAddress()) + "；orderNo=" + nvl(v.orderNo())
                + "；title=" + nvl(v.title()) + "；detection=" + nvl(v.detection())
                + "；dueAt=" + (v.dueAt() == null ? "-" : v.dueAt())
                + "；createAt=" + v.createTime();
    }

    @Tool(description = "查事件流水：从开单到现在的每一步（谁在什么时候做了什么）。摘要与草稿的依据主要来自这里。")
    public String eventTimeline() {
        List<WorkOrderConsolePort.EventView> events = workOrders.timeline(workOrderId);
        if (events.isEmpty()) {
            return "暂无事件流水。";
        }
        StringBuilder sb = new StringBuilder("事件流水（旧→新）：");
        for (WorkOrderConsolePort.EventView e : events) {
            sb.append("\n- [").append(e.eventType()).append("] ").append(e.at())
                    .append(e.operatorId() == null ? "（系统）" : "（操作人 " + e.operatorId() + "）")
                    .append("：").append(nvl(e.detail()));
        }
        return sb.toString();
    }

    @Tool(description = "查证据时间线：开门/关门/物检/代关各采集点的检测结论与 AI 标签。用于说明现场检测说了什么。")
    public String evidenceTimeline() {
        List<WorkOrderConsolePort.EvidenceView> evidences = workOrders.evidenceFor(workOrderId);
        if (evidences.isEmpty()) {
            return "暂无证据记录。";
        }
        StringBuilder sb = new StringBuilder("证据时间线（旧→新）：");
        for (WorkOrderConsolePort.EvidenceView ev : evidences) {
            sb.append("\n- [").append(nvl(ev.moment())).append("/").append(nvl(ev.source())).append("]")
                    .append(" 柜内=").append(nvl(ev.presence()))
                    .append(" 置信度=").append(ev.confidence() == null ? "未给" : ev.confidence())
                    .append(" AI标签=").append(nvl(ev.aiLabel()))
                    .append(" 备注=").append(nvl(ev.note()))
                    .append(" at=").append(ev.at());
        }
        return sb.toString();
    }

    @Tool(description = "查相似已结单：同格口同类型、已人工处理完结的历史工单（标题 + 处理结论原文 + 完结日期）。没有历史会明确说暂无。")
    public String similarResolved() {
        List<WorkOrderConsolePort.SimilarCase> cases = workOrders.similarResolved(workOrderId, 3);
        if (cases.isEmpty()) {
            return "同格口同类型暂无已处理记录。";
        }
        StringBuilder sb = new StringBuilder("相似已结单（新→旧）：");
        for (WorkOrderConsolePort.SimilarCase c : cases) {
            sb.append("\n- [").append(nvl(c.resolvedAt())).append("] ")
                    .append(nvl(c.title())).append(" → ").append(nvl(c.resultNote()));
        }
        return sb.toString();
    }

    private static String nvl(String text) {
        return text == null || text.isBlank() ? "-" : text;
    }
}
