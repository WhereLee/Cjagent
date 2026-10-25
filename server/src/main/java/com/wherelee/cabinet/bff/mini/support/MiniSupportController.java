package com.wherelee.cabinet.bff.mini.support;

import com.wherelee.cabinet.assistant.api.SupportAgentPort;
import com.wherelee.cabinet.servicedesk.api.WorkOrderMetricsPort;
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

@Tag(name = "客户-智能客服", description = "简单业务问题先由 AI 处理，解决不了自动转报工单")
@RestController
@RequestMapping("/api/mini/support")
@Validated
public class MiniSupportController {

    private final SupportAgentPort support;
    private final WorkOrderMetricsPort metrics;

    public MiniSupportController(SupportAgentPort support, WorkOrderMetricsPort metrics) {
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

    public record ChatRequest(
            @Size(max = 32, message = "订单号不合法") String orderNo,
            @NotBlank(message = "消息不能为空") @Size(max = 500, message = "消息最长 500 字") String message) {
    }
}
