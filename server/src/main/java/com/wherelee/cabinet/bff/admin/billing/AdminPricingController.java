package com.wherelee.cabinet.bff.admin.billing;

import com.wherelee.cabinet.funds.api.PricingConsolePort;
import com.wherelee.cabinet.common.annotation.Idempotent;
import com.wherelee.cabinet.common.annotation.OperationLog;
import com.wherelee.cabinet.common.api.R;
import com.wherelee.cabinet.platform.security.Caller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "后台-计价策略")
@RestController
@RequestMapping("/api/admin/pricing")
@Validated
public class AdminPricingController {

    private final PricingConsolePort rules;

    public AdminPricingController(PricingConsolePort rules) {
        this.rules = rules;
    }

    @Operation(summary = "策略列表", description = "含各点位与全局的历史版本，新→旧")
    @PreAuthorize("hasAuthority('pricing:rule:list')")
    @GetMapping("/rules")
    public R<List<PricingConsolePort.PricingRuleView>> list() {
        return R.ok(rules.list());
    }

    @Operation(summary = "当前生效策略", description = "站点优先、回退全局；为空表示该范围仍在吃配置默认价")
    @PreAuthorize("hasAuthority('pricing:rule:list')")
    @GetMapping("/effective")
    public R<PricingConsolePort.PricingRuleView> effective(@RequestParam(required = false) Long siteId) {
        return R.ok(rules.effective(siteId));
    }

    @Operation(summary = "发布前预览", description = "按草稿（或当前生效）算各尺寸在若干时长上的点数，与结算同一算法")
    @PreAuthorize("hasAuthority('pricing:rule:list')")
    @PostMapping("/rules/preview")
    public R<Map<String, Object>> preview(@RequestParam(required = false) Long siteId,
                                         @RequestBody(required = false) PricingConsolePort.Draft draft) {
        return R.ok(rules.preview(siteId, draft));
    }

    @Operation(summary = "发布一版策略", description = "可指定生效时刻（灰度可预约）；旧版自动标记为已替代")
    @OperationLog(module = "pricing", operation = "发布计价策略")
    @Idempotent(message = "该发布正在处理中，请勿重复提交")
    @PreAuthorize("hasAuthority('pricing:rule:publish')")
    @PostMapping("/rules")
    public R<PricingConsolePort.PricingRuleView> publish(@AuthenticationPrincipal Caller current,
                                  @NotNull @Valid @RequestBody PricingConsolePort.Draft request) {
        return R.ok(rules.publish(request, request.siteId(), request.effectiveAt(),
                request.reason(), current.subjectId()));
    }

    @Operation(summary = "回滚到指定版本", description = "实现为【把该版内容再发布一次】，历史链不断且回滚本身可审计")
    @OperationLog(module = "pricing", operation = "回滚计价策略")
    @Idempotent(key = "#ruleId", requireKey = true, message = "该回滚正在处理中")
    @PreAuthorize("hasAuthority('pricing:rule:publish')")
    @PostMapping("/rules/{ruleId}/rollback")
    public R<PricingConsolePort.PricingRuleView> rollback(@AuthenticationPrincipal Caller current,
                                   @PathVariable Long ruleId,
                                   @Valid @RequestBody ReasonRequest request) {
        return R.ok(rules.rollback(ruleId, request.reason(), current.subjectId()));
    }

    public record ReasonRequest(@NotBlank @Size(max = 200) String reason) {
    }
}
