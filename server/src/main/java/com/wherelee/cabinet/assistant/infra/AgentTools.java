package com.wherelee.cabinet.assistant.infra;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.application.storage.StorageOrderFacade;
import com.wherelee.cabinet.application.storage.StorageQueryService;
import com.wherelee.cabinet.assistant.api.SupportAgentPort;
import com.wherelee.cabinet.application.workorder.WorkOrderIntake;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.entity.BizWorkOrder;
import com.wherelee.cabinet.device.api.CommandAction;
import com.wherelee.cabinet.domain.enums.WorkOrderIntent;
import com.wherelee.cabinet.domain.enums.WorkOrderKind;
import com.wherelee.cabinet.domain.enums.WorkOrderSource;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizWorkOrderMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 客服 agent 的工具集（第 22 刀）。
 *
 * <p><b>每次对话 new 一个实例</b>：身份（customerId）与关联订单在构造时由 agent 注入，
 * 三个工具<b>一个身份参数都没有</b>——模型想伪造"帮别人查单"连入口都找不到。
 * 对话期间的动作状态（重开是否已试、是否已建单）记在实例字段上，
 * 天然不会被跨对话串走。
 *
 * <p>工具的返回值是给<b>模型看的</b>：它要把这些内容转述给用户，所以每条返回
 * 都是"事实 + 建议的下一步"，错误也一样——失败不是异常字符串，是可转述的话术。
 * 真正的业务动作全部复用既有服务（查询走 StorageQueryService、开门走 StorageOrderFacade、
 * 建单走 WorkOrderIntake 统一入口），本类不写一行新的业务规则。
 */
public class AgentTools {

    /** 重开一次门的动作：中途取物（临时开）——格口仍归该订单占用，不是新的业务动作。 */
    private static final int SUMMARY_MAX = 200;
    private static final int DETECTION_MAX = 400;
    private static final int TITLE_MAX = 80;

    private final StorageQueryService queries;
    private final StorageOrderFacade doors;
    private final WorkOrderIntake intake;
    private final BizStorageOrderMapper orderMapper;
    private final BizWorkOrderMapper workOrderMapper;
    private final Long customerId;
    private final String orderNo;
    /** 本次对话的追踪号：AI 动作留痕要和人工/系统动作分开。 */
    private final String traceId = UUID.randomUUID().toString();

    private boolean doorRetryAttempted;
    private String lastAttemptSummary;
    private boolean escalated;
    private String ticketNo;
    private final com.wherelee.cabinet.application.workorder.WorkOrderHandoffService handoffs;

    public AgentTools(StorageQueryService queries, StorageOrderFacade doors,
                      WorkOrderIntake intake, BizStorageOrderMapper orderMapper,
                      BizWorkOrderMapper workOrderMapper,
                      com.wherelee.cabinet.application.workorder.WorkOrderHandoffService handoffs,
                      SupportAgentPort.SupportSession session) {
        this.queries = queries;
        this.doors = doors;
        this.intake = intake;
        this.orderMapper = orderMapper;
        this.workOrderMapper = workOrderMapper;
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

    /** 查本人寄存单概览。与本次对话关联的订单排第一并打标，让模型一眼认出"说的是哪张"。 */
    @Tool(description = "查询当前用户本人的寄存单概览：订单号、柜机/格口编号、状态、有无待取回标记、已结算与欠费点数。"
            + "与本次对话关联的订单排在第一个并带[本次相关]标记。查不到就说明用户名下没有寄存单。")
    public String myOrderOverview() {
        try {
            List<StorageQueryService.OrderView> views = queries.myOrders(customerId, 10);
            if (views.isEmpty()) {
                return "该用户名下没有寄存单。";
            }
            StringBuilder sb = new StringBuilder("共 ").append(views.size()).append(" 张（按时间倒序）：");
            for (StorageQueryService.OrderView v : views) {
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

    /**
     * 替用户重开一次柜门。<b>一次对话只允许试一次</b>：开不了就转报，不许反复摇设备
     * （21 刀已确认无回执重试会真开两次门，客服侧更要克制）。
     */
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
            // 设备无回执时 openDoor 返回的是“已下发、请确认”，不是“门已打开”。
            // 原话必须转述：把“已下发”说成“已打开”，用户站在柜前看到门没动就会认为客服在骗人。
            if (result != null && result.message() != null && !result.message().isBlank()) {
                lastAttemptSummary = "OPEN_TEMP：" + result.message();
                return "开门结果：" + result.message()
                        + "。请按这句话告知用户，并提醒用户确认柜门是否真的弹开。";
            }
            lastAttemptSummary = "OPEN_TEMP：指令执行成功，设备未返回具体话术";
            return "开门指令执行成功：柜门已重新打开。请提醒用户尽快完成取放，并关好柜门。";
        } catch (BizException e) {
            // 业务异常的 message 本来就是面向用户的话术（离线/锁定/状态不符），直接给模型转述
            lastAttemptSummary = "OPEN_TEMP 失败：" + e.getMessage();
            return "开门失败：" + e.getMessage() + "。请使用 raiseTicket 转报人工处理。";
        } catch (Exception e) {
            lastAttemptSummary = "OPEN_TEMP 失败：设备暂时没有响应";
            return "开门失败：设备暂时没有响应。请使用 raiseTicket 转报人工处理。";
        }
    }

    /**
     * 转报工单。类型只开放两种（设备故障 / 用户自述门未关），
     * 建单走 WorkOrderIntake 统一入口：快照补齐、幂等合并都是既有规则，本工具只递话。
     * 建出的单进线上队列，派线下仍是业务员的决策——agent 不越权。
     */
    /** 兼容旧调用：把旧类型翻译成 intent，真正的分诊由系统规则做。 */
    public String raiseTicket(String issueKind, String issueSummary) {
        return raiseTicket(intentOfKind(issueKind).name(), issueSummary, null, null);
    }

    /** 兼容三参调用（测试与旧路径）：不提供“用户是否在原地”。 */
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
        // 归属守卫：单不存在或不是本人的，一律"未找到"——不给模型任何探测他人订单的口子
        if (order == null || !customerId.equals(order.getCustomerId())) {
            return "未找到这张订单，无法转报（归属校验未通过）。";
        }
        WorkOrderIntent intent = parseIntent(issueIntent);
        // 只有“门打不开”有对应的自助动作；其他现象没有可重试的远程动作，不强制试。
        if (intent == WorkOrderIntent.DOOR_NOT_OPEN && !doorRetryAttempted) {
            return "门打不开属于可自助尝试的问题：请先调用 retryMyDoor 尝试一次，并把结果写进 attemptSummary。";
        }
        String attempt = firstNonBlank(attemptSummary, lastAttemptSummary);
        try {
            // “用户自述”必须标明：这段文字不是设备检测结论，不能让业务员当成现场事实。
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

    /** 查本人未关闭工单的进度：只读，不返回内部备注。 */
    @Tool(description = "查询当前用户本人未关闭的工单进度：工单号、类型、状态、优先级、要求处理时刻、标题。"
            + "不返回工单内部备注。关联了订单时只查该订单，没有关联订单时查本人全部未关单。")
    public String myTicketStatus() {
        try {
            var query = Wrappers.<BizWorkOrder>lambdaQuery()
                    .eq(BizWorkOrder::getCustomerId, customerId)
                    .eq(BizWorkOrder::getActiveFlag, 1)
                    .orderByDesc(BizWorkOrder::getCreateTime)
                    .last("limit 5");
            if (!noOrder()) {
                query.eq(BizWorkOrder::getOrderNo, orderNo);
            }
            List<BizWorkOrder> list = workOrderMapper.selectList(query);
            if (list.isEmpty()) {
                return "当前没有未关闭的工单。";
            }
            StringBuilder sb = new StringBuilder("当前未关闭工单：");
            for (BizWorkOrder o : list) {
                sb.append("\n- 工单号=").append(o.getId())
                        .append(" 类型=").append(o.getCategory() == null
                                ? (o.getType() == null ? "-" : o.getType().name()) : o.getCategory().name())
                        .append(" 状态=").append(o.getStatus() == null ? "-" : o.getStatus().name())
                        .append(" 优先级=").append(o.getPriority() == null ? "-" : o.getPriority().name())
                        .append(" 要求处理=").append(o.getResolveDueAt() == null ? "未设置" : o.getResolveDueAt())
                        .append(" 标题=").append(nullSafe(o.getTitle()));
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
                // 模型给了未知现象：落 OTHER，由规则进人工分拣，不硬派。
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
