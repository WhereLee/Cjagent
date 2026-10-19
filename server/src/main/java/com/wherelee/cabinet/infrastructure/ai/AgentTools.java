package com.wherelee.cabinet.infrastructure.ai;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.application.storage.StorageOrderFacade;
import com.wherelee.cabinet.application.storage.StorageQueryService;
import com.wherelee.cabinet.application.support.SupportAgentPort;
import com.wherelee.cabinet.application.workorder.WorkOrderIntake;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.entity.BizWorkOrder;
import com.wherelee.cabinet.domain.enums.CommandAction;
import com.wherelee.cabinet.domain.enums.WorkOrderCategory;
import com.wherelee.cabinet.domain.enums.WorkOrderIntent;
import com.wherelee.cabinet.domain.enums.WorkOrderKind;
import com.wherelee.cabinet.domain.enums.WorkOrderSource;
import com.wherelee.cabinet.domain.enums.WorkOrderType;
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

    public AgentTools(StorageQueryService queries, StorageOrderFacade doors,
                      WorkOrderIntake intake, BizStorageOrderMapper orderMapper,
                      BizWorkOrderMapper workOrderMapper,
                      SupportAgentPort.SupportSession session) {
        this.queries = queries;
        this.doors = doors;
        this.intake = intake;
        this.orderMapper = orderMapper;
        this.workOrderMapper = workOrderMapper;
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
    /** 兼容旧调用：设备故障类如果没有先调用 retryMyDoor，会被下面的硬校验拒绝。 */
    public String raiseTicket(String issueKind, String issueSummary) {
        return raiseTicket(issueKind, issueSummary, null, null);
    }

    @Tool(description = "解决不了时转报工单给人工。类型只支持两种："
            + "DEVICE_FAULT（门打不开/关不上/设备疑似故障）或 DOOR_LEFT_OPEN（用户自述门没关好）。"
            + "设备故障类必须先调用 retryMyDoor；没有尝试记录会被拒绝。"
            + "备注要写清：用户反映什么、现场状态、已尝试过什么、结果如何。")
    public String raiseTicket(
            @ToolParam(description = "工单类型：DEVICE_FAULT 或 DOOR_LEFT_OPEN") String issueKind,
            @ToolParam(description = "给业务员的备注（不超过200字）：用户反映+现场状态+已试过什么+结果") String issueSummary,
            @ToolParam(description = "用户现象：DOOR_NOT_OPEN/DOOR_NOT_CLOSE/ITEM_MISSING/ITEM_LEFT/DEVICE_OFFLINE/BILLING_DISPUTE/PICKUP_CODE/OTHER", required = false) String issueIntent,
            @ToolParam(description = "已尝试动作与结果，例如：OPEN_TEMP 已下发无回执/设备离线", required = false) String attemptSummary) {
        if (noOrder()) {
            return "本次对话没有关联订单，无法转报：请让用户从订单详情页进入客服后再报。";
        }
        String kind = issueKind == null ? "" : issueKind.trim().toUpperCase(Locale.ROOT);
        WorkOrderType type = switch (kind) {
            case "DEVICE_FAULT" -> WorkOrderType.DEVICE_FAULT;
            case "DOOR_LEFT_OPEN" -> WorkOrderType.DOOR_NOT_CLOSED;
            default -> null;
        };
        if (type == null) {
            return "工单类型只支持 DEVICE_FAULT 或 DOOR_LEFT_OPEN，请修正后再转报。";
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
        // 硬约束：设备故障类必须先由工具真正尝试过一次；模型自己写“已尝试”不算记录。
        if (type == WorkOrderType.DEVICE_FAULT && !doorRetryAttempted) {
            return "设备故障类转报前必须先调用 retryMyDoor 尝试一次，并把结果写进 attemptSummary。";
        }
        String attempt = firstNonBlank(attemptSummary, lastAttemptSummary);
        WorkOrderIntent intent = parseIntent(issueIntent, type);
        WorkOrderCategory category = type == WorkOrderType.DEVICE_FAULT
                ? WorkOrderCategory.DOOR_FAULT : WorkOrderCategory.DOOR_NOT_CLOSED;
        try {
            // “用户自述”必须标明：这段文字不是设备检测结论，不能让业务员当成现场事实。
            Long workOrderId = intake.onOrderEvent(order, type, false,
                    truncate("AI 客服转报（用户自述，未经设备核实）：" + summary
                            + "；已尝试：" + (attempt == null ? "未记录" : attempt), DETECTION_MAX),
                    truncate("AI 客服转报（订单 " + orderNo + "）", TITLE_MAX),
                    LocalDateTime.now().plusMinutes(120),
                    WorkOrderKind.REQUEST, WorkOrderSource.AI, intent, category, null,
                    handoffJson(summary, attempt), traceId);
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
                        .append(" 类型=").append(o.getKind() == null ? "-" : o.getKind().name())
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

    private WorkOrderIntent parseIntent(String raw, WorkOrderType type) {
        if (raw != null && !raw.isBlank()) {
            try {
                return WorkOrderIntent.of(raw);
            } catch (IllegalArgumentException ignored) {
                // 模型给了未知现象：按类型回退，不因此拒绝整张单
            }
        }
        return type == WorkOrderType.DEVICE_FAULT
                ? WorkOrderIntent.DOOR_NOT_OPEN : WorkOrderIntent.DOOR_NOT_CLOSE;
    }

    private String handoffJson(String summary, String attempt) {
        return "{\"orderNo\":\"" + escape(orderNo)
                + "\",\"userStatement\":\"" + escape(summary)
                + "\",\"attempt\":\"" + escape(attempt == null ? "未记录" : attempt)
                + "\",\"traceId\":\"" + escape(traceId) + "\"}";
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null || second.isBlank() ? null : second.trim();
    }

    private static String escape(String raw) {
        return raw == null ? "" : raw.replace("\\", "\\\\").replace("\"", "\\\"");
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
