package com.wherelee.cabinet.bff.mini.workorder;

import com.wherelee.cabinet.servicedesk.api.WorkOrderConsolePort;
import com.wherelee.cabinet.common.api.R;
import com.wherelee.cabinet.common.annotation.RateLimit;
import com.wherelee.cabinet.platform.security.Caller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户侧工单：只看自己的、只能确认或反馈没解决。
 *
 * <p>视图是 {@link WorkOrderConsolePort.CustomerView}，不是后台的 {@code View}：
 * 交接 JSON、快照电话、指派对象属于内部流转细节，不能顺手塞进小程序。
 *
 * <p>“问题已解决/还没解决”都只对本人已存在的单生效：
 * 确认写 USER_CONFIRMED（关闭依据是用户本人），反馈没解决则重开并把次数 +1。
 */
@Tag(name = "客户-工单", description = "我的工单、确认已解决、反馈还没解决")
@RestController
@RequestMapping("/api/mini/work-orders")
public class MiniWorkOrderController {

    private final WorkOrderConsolePort workOrders;

    public MiniWorkOrderController(WorkOrderConsolePort workOrders) {
        this.workOrders = workOrders;
    }

    @Operation(summary = "我的工单", description = "最近若干张；默认 20 张，上限 50")
    @RateLimit(limit = 60, windowSeconds = 60)
    @PreAuthorize("hasRole('CUSTOMER')")
    @GetMapping
    public R<List<WorkOrderConsolePort.CustomerView>> myOrders(
            @AuthenticationPrincipal Caller current,
            @RequestParam(defaultValue = "20") int limit) {
        return R.ok(workOrders.myOrders(current.subjectId(), limit));
    }

    @Operation(summary = "工单详情", description = "只能看自己的工单")
    @RateLimit(limit = 60, windowSeconds = 60)
    @PreAuthorize("hasRole('CUSTOMER')")
    @GetMapping("/{id}")
    public R<WorkOrderConsolePort.CustomerView> detail(@AuthenticationPrincipal Caller current,
                                                   @PathVariable Long id) {
        return R.ok(workOrders.myOrder(id, current.subjectId()));
    }

    @Operation(summary = "确认已解决 / 反馈还没解决",
            description = "solved=true 关闭为 USER_CONFIRMED；solved=false 重开本人已关闭的工单")
    @RateLimit(limit = 20, windowSeconds = 60)
    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping("/{id}/confirm")
    public R<Long> confirm(@AuthenticationPrincipal Caller current, @PathVariable Long id,
                           @Valid @RequestBody ConfirmCommand cmd) {
        if (cmd.solved()) {
            workOrders.confirmResolved(id, current.subjectId(), cmd.note());
            return R.ok(id);
        }
        return R.ok(workOrders.reopen(id, current.subjectId(), cmd.note()));
    }

    /** solved 必填：同一入口两种相反动作，缺省值会悄悄替用户选一个。 */
    public record ConfirmCommand(boolean solved, @Size(max = 300) String note) {
    }
}
