package com.wherelee.cabinet.interfaces.mini.support;

import com.wherelee.cabinet.assistant.api.SupportAgentPort;
import com.wherelee.cabinet.application.workorder.WorkOrderMetrics;
import com.wherelee.cabinet.common.annotation.OperationLog;
import com.wherelee.cabinet.common.annotation.RateLimit;
import com.wherelee.cabinet.common.api.R;
import com.wherelee.cabinet.platform.security.Caller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 客户-智能客服（第 22 刀）。
 *
 * <p>用户遇到简单业务问题先找 AI：查自己的单、重开一次柜门、计费口径答疑；
 * 解决不了由 AI 转报工单（进线上队列，派线下仍是业务员的决策）。
 * 身份只来自登录态（{@code Caller}），请求体里没有也不允许有 customerId。
 *
 * <p>限流是硬约束而不是调优项：工具循环的每一轮都在花钱（模型 token）与动真物
 * （远程开门、建单），不给刷对话留门。AI 不可用时端口实现自己兜底，
 * 这里永远拿到的是一条可展示的话术。
 */
@Tag(name = "客户-智能客服", description = "简单业务问题先由 AI 处理，解决不了自动转报工单")
@RestController
@RequestMapping("/api/mini/support")
@Validated
public class MiniSupportController {

    private final SupportAgentPort support;
    private final WorkOrderMetrics metrics;

    public MiniSupportController(SupportAgentPort support, WorkOrderMetrics metrics) {
        this.support = support;
        this.metrics = metrics;
    }

    @Operation(summary = "客服对话",
            description = "orderNo 可选（从订单详情进入时带上）；返回 AI 话术与是否已转报（escalated + ticketNo）")
    @OperationLog(module = "support", operation = "AI客服对话")
    @RateLimit(limit = 10, windowSeconds = 60, message = "提问太频繁了，请稍等片刻再试")
    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping("/chat")
    public R<SupportAgentPort.AgentReply> chat(@AuthenticationPrincipal Caller current,
                                               @Valid @RequestBody ChatRequest request) {
        SupportAgentPort.AgentReply reply = support.chat(new SupportAgentPort.SupportSession(
                current.subjectId(), request.orderNo(), request.message()));
        metrics.chat(reply.escalated(), reply.degraded());
        return R.ok(reply);
    }

    /** 对话请求体。没有 customerId 字段：身份永远来自登录态。 */
    public record ChatRequest(
            @Size(max = 32, message = "订单号不合法") String orderNo,
            @NotBlank(message = "消息不能为空") @Size(max = 500, message = "消息最长 500 字") String message) {
    }
}
