package com.wherelee.cabinet.interfaces.admin.workorder;

import com.wherelee.cabinet.application.workorder.WorkOrderService;
import com.wherelee.cabinet.common.api.R;
import com.wherelee.cabinet.common.annotation.Idempotent;
import com.wherelee.cabinet.common.annotation.OperationLog;
import com.wherelee.cabinet.infrastructure.security.VerifiedToken;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
 * 工单队列与流转（19B）。
 *
 * <p><b>两条队列两个权限点</b>：线上业务员看不到也办不了线下队列的反向操作，反之亦然。
 * 这不是权限洁癖——“谁该做这一步”是工单流转的语义本身，全靠一个管理员账号点完，
 * 留痕里就分不出责任。
 *
 * <p>返回的全是应用层视图（{@link WorkOrderService.View}），不回传实体：
 * 实体带着 active_flag、version 这些流转内部字段，暴露出去迟早有人拿它们当业务状态用
 * （ArchUnit 也会当场拦下）。
 */
@Tag(name = "后台-工单", description = "线上/线下工单队列、认领、处理反馈与流转留痕")
@RestController
@RequestMapping("/api/admin/work-orders")
public class AdminWorkOrderController {

    private final WorkOrderService workOrders;

    public AdminWorkOrderController(WorkOrderService workOrders) {
        this.workOrders = workOrders;
    }

    @Operation(summary = "线上工单队列", description = "紧急在前、开单早的先办；默认只给未关闭的")
    @PreAuthorize("hasAuthority('workorder:online:list')")
    @GetMapping("/online")
    public R<List<WorkOrderService.View>> online(@RequestParam(defaultValue = "true") boolean openOnly) {
        return R.ok(workOrders.queue(WorkOrderService.SIDE_ONLINE, openOnly));
    }

    @Operation(summary = "线下工单队列", description = "线上业务员判断“需跑一趟”后派发到这里的工单")
    @PreAuthorize("hasAuthority('workorder:field:list')")
    @GetMapping("/field")
    public R<List<WorkOrderService.View>> field(@RequestParam(defaultValue = "true") boolean openOnly) {
        return R.ok(workOrders.queue(WorkOrderService.SIDE_FIELD, openOnly));
    }

    @Operation(summary = "认领工单", description = "并发下只有一个人认领成功，其余拿到“已被他人认领”")
    @OperationLog(module = "workorder", operation = "认领工单")
    @Idempotent(key = "#current.subjectId() + ':' + #id", requireKey = false)
    @PreAuthorize("hasAuthority('workorder:claim')")
    @PostMapping("/{id}/claim")
    public R<Void> claim(@AuthenticationPrincipal VerifiedToken current, @PathVariable Long id) {
        workOrders.claim(id, current.subjectId());
        return R.ok();
    }

    /**
     * 处理反馈。<b>事由必填</b>：一张没有结论说明的工单，等于整个流转白做。
     *
     * @param needOffline true 时系统另发一张线下工单（父单落 RESOLVED，子单进 FIELD 队列）
     */
    @Operation(summary = "处理并反馈", description = "结论必填；needOffline=true 时派发线下工单")
    @OperationLog(module = "workorder", operation = "处理工单")
    @Idempotent(key = "#cmd.requestId", requireKey = true, message = "该处理请求已在提交")
    @PreAuthorize("hasAuthority('workorder:resolve')")
    @PostMapping("/{id}/resolve")
    public R<Long> resolve(@AuthenticationPrincipal VerifiedToken current, @PathVariable Long id,
                           @Valid @RequestBody ResolveCommand cmd) {
        return R.ok(workOrders.resolve(id, current.subjectId(), cmd.resultNote(), cmd.needOffline()));
    }

    @Operation(summary = "工单流转留痕", description = "从开单到关闭的每一步，含系统自动转化")
    @PreAuthorize("hasAuthority('workorder:online:list')")
    @GetMapping("/{id}/timeline")
    public R<List<WorkOrderService.EventView>> timeline(@PathVariable Long id) {
        return R.ok(workOrders.timeline(id));
    }

    /** requestId 由前端生成：处理反馈会派生子单，重放不能派出第二张。 */
    public record ResolveCommand(@NotBlank @Size(max = 200) String requestId,
                                 @NotBlank @Size(max = 400) String resultNote,
                                 boolean needOffline) {
    }
}
