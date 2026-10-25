package com.wherelee.cabinet.assistant.infra;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.storage.api.StorageOrderCommandPort;
import com.wherelee.cabinet.storage.api.StorageQueryPort;
import com.wherelee.cabinet.assistant.api.SupportAgentPort;
import com.wherelee.cabinet.servicedesk.api.WorkOrderHandoffPort;
import com.wherelee.cabinet.servicedesk.api.WorkOrderIntakePort;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.servicedesk.api.WorkOrderPort;
import com.wherelee.cabinet.device.api.CommandAction;
import com.wherelee.cabinet.servicedesk.api.WorkOrderIntent;
import com.wherelee.cabinet.servicedesk.api.WorkOrderKind;
import com.wherelee.cabinet.servicedesk.api.WorkOrderSource;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class AgentTools {

    private static final int SUMMARY_MAX = 200;
    private static final int DETECTION_MAX = 400;
    private static final int TITLE_MAX = 80;

    private final StorageQueryPort queries;
    private final StorageOrderCommandPort doors;
    private final WorkOrderIntakePort intake;
    private final BizStorageOrderMapper orderMapper;
    private final WorkOrderPort workOrderPort;
    private final Long customerId;
    private final String orderNo;
    private final String traceId = UUID.randomUUID().toString();

    private boolean doorRetryAttempted;
    private String lastAttemptSummary;
    private boolean escalated;
    private String ticketNo;
    private final WorkOrderHandoffPort handoffs;

    public AgentTools(StorageQueryPort queries, StorageOrderCommandPort doors,
                      WorkOrderIntakePort intake, BizStorageOrderMapper orderMapper,
                      WorkOrderPort workOrderPort,
                      WorkOrderHandoffPort handoffs,
                      SupportAgentPort.SupportSession session) {
        this.queries = queries;
        this.doors = doors;
        this.intake = intake;
        this.orderMapper = orderMapper;
        this.workOrderPort = workOrderPort;
        this.handoffs = handoffs;
        this.customerId = session.customerId();
        this.orderNo = session.orderNo();
    }

    public boolean escalated() {
        return escalated;
    }

    public String ticketNo() {
        return ticketNo;
    }

    @Tool(description = "查询当前用户本人的寄存单概览：订单号、柜机/格口编号、状态、有无待取回标记、已结算与欠费点数。"
            + "与本次对话关联的订单排在第一个并带[本次相关]标记。查不到就说明用户名下没有寄存单。")
    public String myOrderOverview() {
        try {
            List<StorageQueryPort.OrderView> views = queries.myOrders(customerId, 10);
            if (views.isEmpty()) {
                return "该用户名下没有寄存单。";
            }
            StringBuilder sb = new StringBuilder("共 ").append(views.size()).append(" 张（按时间倒序）：");
            for (StorageQueryPort.OrderView v : views) {
                sb.append("\n- orderNo=").append(v.orderNo())
                        .append(" 柜机=").append(nullSafe(v.cabinetNo()))
                        .append(" 格口=").append(nullSafe(v.slotNo()))
                        .append(" 规格=").append(nullSafe(v.sizeType()))
                        .append(" 状态=").append(nullSafe(v.status()))
                        .append(" 待取回=").append(v.leftoverRetrievable())
                        .append(" 已结算=").append(v.settledPoints()).append("点")
                        .append(" 欠费=").append(v.arrearsPoints()).append("点");
                if (orderNo != null && orderNo.equals(v.orderNo())) {
                    sb.append(" [本次相关]");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return "查询失败：" + e.getMessage() + "。如果涉及费用争议，建议转报人工核实。";
        }
    }

    @Tool(description = "替用户重开一次柜门（远程开门，只允许尝试一次）。需要本次对话关联了订单。"
            + "失败时按返回话术告知用户，并使用 raiseTicket 转报，不要再次调用本工具。")
    public String retryMyDoor() {
        if (noOrder()) {
            lastAttemptSummary = "未尝试：本次对话没有关联订单";
            return "本次对话没有关联到具体订单：请让用户从「我的寄存单 - 订单详情」进入客服，"
                    + "或直接在订单详情页使用开柜功能。";
        }
        if (doorRetryAttempted) {
            return "本次对话已经为这扇柜门尝试过一次远程开门：按规则不能重复尝试，"
                    + "请使用 raiseTicket 转报人工处理。";
        }
        doorRetryAttempted = true;
        try {
            var result = doors.openDoor(customerId, orderNo, CommandAction.OPEN_TEMP);
            if (result != null && result.message() != null && !result.message().isBlank()) {
                lastAttemptSummary = "OPEN_TEMP：" + result.message();
                return "开门结果：" + result.message()
                        + "。请按这句话告知用户，并提醒用户确认柜门是否真的弹开。";
            }
            lastAttemptSummary = "OPEN_TEMP：指令执行成功，设备未返回具体话术";
            return "开门指令执行成功：柜门已重新打开。请提醒用户尽快完成取放，并关好柜门。";
        } catch (BizException e) {
            lastAttemptSummary = "OPEN_TEMP 失败：" + e.getMessage();
            return "开门失败：" + e.getMessage() + "。请使用 raiseTicket 转报人工处理。";
        } catch (Exception e) {
            lastAttemptSummary = "OPEN_TEMP 失败：设备暂时没有响应";
            return "开门失败：设备暂时没有响应。请使用 raiseTicket 转报人工处理。";
        }
    }

    public String raiseTicket(String issueKind, String issueSummary) {
        return raiseTicket(intentOfKind(issueKind).name(), issueSummary, null, null);
    }

    public String raiseTicket(String issueIntent, String issueSummary, String attemptSummary) {
        return raiseTicket(issueIntent, issueSummary, attemptSummary, null);
    }

    @Tool(description = "解决不了时转报工单给人工。你只描述用户现象（intent），不要选工单类型；"
            + "系统会按现象和事实分诊。门打不开（DOOR_NOT_OPEN）必须先调用 retryMyDoor；"
            + "其他现象可附上已尝试动作。备注要写清：用户反映什么、现场状态、已尝试过什么、结果如何。")
    public String raiseTicket(
            @ToolParam(description = "用户现象：DOOR_NOT_OPEN/DOOR_NOT_CLOSE/ITEM_MISSING/ITEM_LEFT/DEVICE_OFFLINE/BILLING_DISPUTE/PICKUP_CODE/OTHER") String issueIntent,
            @ToolParam(description = "给业务员的备注（不超过200字）：用户反映+现场状态+已试过什么+结果") String issueSummary,
            @ToolParam(description = "已尝试动作与结果，例如：OPEN_TEMP 已下发无回执/设备离线", required = false) String attemptSummary,
            @ToolParam(description = "用户是否还在柜机旁（true/false）；不知道就不传，别猜", required = false) Boolean userOnSite) {
        if (noOrder()) {
            return "本次对话没有关联订单，无法转报：请让用户从订单详情页进入客服后再报。";
        }
        String summary = issueSummary == null ? "" : issueSummary.trim();
        if (summary.isEmpty()) {
            return "备注不能为空：请写清用户反映什么、现场状态、已尝试过什么、结果如何。";
        }

        BizStorageOrder order = orderMapper.selectOne(Wrappers.<BizStorageOrder>lambdaQuery()
                .eq(BizStorageOrder::getOrderNo, orderNo));
        if (order == null || !customerId.equals(order.getCustomerId())) {
            return "未找到这张订单，无法转报（归属校验未通过）。";
        }
        WorkOrderIntent intent = parseIntent(issueIntent);
        if (intent == WorkOrderIntent.DOOR_NOT_OPEN && !doorRetryAttempted) {
            return "门打不开属于可自助尝试的问题：请先调用 retryMyDoor 尝试一次，并把结果写进 attemptSummary。";
        }
        String attempt = firstNonBlank(attemptSummary, lastAttemptSummary);
        try {
            Long workOrderId = intake.onOrderEvent(order, null, false,
                    truncate("AI 客服转报（用户自述，未经设备核实）：" + summary
                            + "；已尝试：" + (attempt == null ? "未记录" : attempt), DETECTION_MAX),
                    truncate("AI 客服转报（订单 " + orderNo + "）", TITLE_MAX),
                    LocalDateTime.now().plusMinutes(120),
                    WorkOrderKind.REQUEST, WorkOrderSource.AI, intent, null, null,
                    handoffs.aiReport(orderNo, summary, attempt, userOnSite, intent, traceId), traceId);
            escalated = true;
            ticketNo = String.valueOf(workOrderId);
            return "转报成功，工单号 " + workOrderId
                    + "。请告知用户：已为他转报，会有工作人员联系，请保持手机畅通。";
        } catch (Exception e) {
            return "转报失败：" + e.getMessage() + "。请告知用户稍后再试或直接使用订单页报障。";
        }
    }

    @Tool(description = "查询当前用户本人未关闭的工单进度：工单号、类型、状态、优先级、要求处理时刻、标题。"
            + "不返回工单内部备注。关联了订单时只查该订单，没有关联订单时查本人全部未关单。")
    public String myTicketStatus() {
        try {
            List<WorkOrderPort.WorkOrderBrief> list = workOrderPort.openOfCustomer(customerId,
                    noOrder() ? null : orderNo, 5);
            if (list.isEmpty()) {
                return "当前没有未关闭的工单。";
            }
            StringBuilder sb = new StringBuilder("当前未关闭工单：");
            for (WorkOrderPort.WorkOrderBrief o : list) {
                sb.append("\n- 工单号=").append(o.id())
                        .append(" 类型=").append(o.category() == null ? (o.type() == null ? "-" : o.type()) : o.category())
                        .append(" 状态=").append(o.status() == null ? "-" : o.status())
                        .append(" 优先级=").append(o.priority() == null ? "-" : o.priority())
                        .append(" 要求处理=").append(o.resolveDueAt() == null ? "未设置" : o.resolveDueAt())
                        .append(" 标题=").append(nullSafe(o.title()));
            }
            return sb.toString();
        } catch (Exception e) {
            return "查询失败：" + e.getMessage() + "。";
        }
    }

    private WorkOrderIntent parseIntent(String raw) {
        if (raw != null && !raw.isBlank()) {
            try {
                return WorkOrderIntent.of(raw);
            } catch (IllegalArgumentException ignored) {
            }
        }
        return WorkOrderIntent.OTHER;
    }

    private WorkOrderIntent intentOfKind(String issueKind) {
        String kind = issueKind == null ? "" : issueKind.trim().toUpperCase(Locale.ROOT);
        return switch (kind) {
            case "DEVICE_FAULT" -> WorkOrderIntent.DOOR_NOT_OPEN;
            case "DOOR_LEFT_OPEN" -> WorkOrderIntent.DOOR_NOT_CLOSE;
            default -> WorkOrderIntent.OTHER;
        };
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null || second.isBlank() ? null : second.trim();
    }

    private boolean noOrder() {
        return orderNo == null || orderNo.isBlank();
    }

    private static String nullSafe(String text) {
        return text == null ? "-" : text;
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
