package com.wherelee.cabinet.interfaces.admin.workorder;

import com.wherelee.cabinet.assistant.api.WorkOrderBriefPort;
import com.wherelee.cabinet.servicedesk.app.WorkOrderService;
import com.wherelee.cabinet.servicedesk.app.WorkOrderTriageService;
import com.wherelee.cabinet.common.api.PageQuery;
import com.wherelee.cabinet.common.api.PageResult;
import com.wherelee.cabinet.common.api.R;
import com.wherelee.cabinet.common.annotation.Idempotent;
import com.wherelee.cabinet.common.annotation.OperationLog;
import com.wherelee.cabinet.common.annotation.RateLimit;
import com.wherelee.cabinet.domain.enums.CloseReason;
import com.wherelee.cabinet.platform.security.Caller;
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
 * <p>例外只有单据详情（timeline/evidence）：认领与处理都要求先能看单，
 * 所以持有任一队列查看权的人都放行——否则线下运维认领之后寸步难行。
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
    private final com.wherelee.cabinet.notify.api.NoticePort notices;
    private final WorkOrderBriefPort briefs;
    private final WorkOrderTriageService triage;

    public AdminWorkOrderController(WorkOrderService workOrders,
                                    com.wherelee.cabinet.notify.api.NoticePort notices,
                                    WorkOrderBriefPort briefs,
                                    WorkOrderTriageService triage) {
        this.workOrders = workOrders;
        this.notices = notices;
        this.briefs = briefs;
        this.triage = triage;
    }

    @Operation(summary = "刷新分诊规则缓存", description = "规则表改完后调用；不清缓存时下一次请求仍用旧规则")
    @OperationLog(module = "workorder", operation = "刷新分诊规则")
    @PreAuthorize("hasAuthority('workorder:resolve')")
    @PostMapping("/triage/reload")
    public R<Void> reloadTriage() {
        triage.reload();
        return R.ok();
    }

    /**
     * 待打电话清单（19D）：系统没有外呼能力，所以这里列出的是“人必须去打的电话”，
     * 打完必须回填结果。它不是装饰：没有这张清单，“10 分钟打电话提醒”就只是一句口说法。
     */
    @Operation(summary = "待联系客户的电话任务", description = "阶梯升到“必须处理”时生成，未回填前一直在列")
    @PreAuthorize("hasAuthority('workorder:online:list')")
    @GetMapping("/pending-calls")
    public R<java.util.List<com.wherelee.cabinet.notify.api.NoticePort.View>> pendingCalls() {
        return R.ok(notices.pendingCalls());
    }

    @Operation(summary = "回填电话结果", description = "联系结果必填：联系上了/未接/约定多久处理")
    @OperationLog(module = "workorder", operation = "回填联系结果")
    @Idempotent(key = "#current.subjectId() + ':call:' + #id", requireKey = false)
    @PreAuthorize("hasAuthority('workorder:resolve')")
    @PostMapping("/notices/{id}/call-done")
    public R<Void> callDone(@AuthenticationPrincipal Caller current, @PathVariable Long id,
                            @Valid @RequestBody CallDoneCommand cmd) {
        notices.markCallDone(id, cmd.resultNote());
        return R.ok();
    }

    /** 回填内容：一句人话的结论，空得拒。 */
    public record CallDoneCommand(@NotBlank @Size(max = 300) String resultNote) {
    }

    @Operation(summary = "线上工单队列", description = "紧急在前、开单早的先办；默认只给未关闭的；支持统一分页与排序（createTime/dueAt）")
    @PreAuthorize("hasAuthority('workorder:online:list')")
    @GetMapping("/online")
    public R<PageResult<WorkOrderService.View>> online(@Valid PageQuery query,
                                                       @RequestParam(defaultValue = "true") boolean openOnly) {
        return R.ok(workOrders.queue(WorkOrderService.SIDE_ONLINE, openOnly, query));
    }

    @Operation(summary = "线下工单队列", description = "线上业务员判断“需跑一趟”后派发到这里的工单；支持统一分页与排序")
    @PreAuthorize("hasAuthority('workorder:field:list')")
    @GetMapping("/field")
    public R<PageResult<WorkOrderService.View>> field(@Valid PageQuery query,
                                                      @RequestParam(defaultValue = "true") boolean openOnly) {
        return R.ok(workOrders.queue(WorkOrderService.SIDE_FIELD, openOnly, query));
    }

    @Operation(summary = "认领工单", description = "并发下只有一个人认领成功，其余拿到“已被他人认领”")
    @OperationLog(module = "workorder", operation = "认领工单")
    @Idempotent(key = "#current.subjectId() + ':' + #id", requireKey = false)
    @PreAuthorize("hasAuthority('workorder:claim')")
    @PostMapping("/{id}/claim")
    public R<Void> claim(@AuthenticationPrincipal Caller current, @PathVariable Long id) {
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
    // 派线下是独立权限点：能关线上单不等于能派人跑现场。needOffline=false 时不需要它。
    @PreAuthorize("hasAuthority('workorder:resolve') and (!#cmd.needOffline() or hasAuthority('workorder:dispatch-field'))")
    @PostMapping("/{id}/resolve")
    public R<Long> resolve(@AuthenticationPrincipal Caller current, @PathVariable Long id,
                           @Valid @RequestBody ResolveCommand cmd) {
        return R.ok(workOrders.resolve(id, current.subjectId(), cmd.resultNote(), cmd.needOffline(),
                cmd.closeReason()));
    }

    /** 暂停/恢复：只停 SLA 两个时限的前进，不改工单状态（等配件、等用户到场）。 */
    @Operation(summary = "暂停SLA", description = "等配件/等用户到场时暂停两个时限；暂停时长不计入 SLA")
    @OperationLog(module = "workorder", operation = "暂停SLA")
    @PreAuthorize("hasAuthority('workorder:resolve')")
    @PostMapping("/{id}/sla/pause")
    public R<Void> pauseSla(@AuthenticationPrincipal Caller current, @PathVariable Long id) {
        workOrders.pauseSla(id, current.subjectId());
        return R.ok();
    }

    @Operation(summary = "恢复SLA", description = "把暂停时长补回响应/解决时限，并重新登记到点任务")
    @OperationLog(module = "workorder", operation = "恢复SLA")
    @PreAuthorize("hasAuthority('workorder:resolve')")
    @PostMapping("/{id}/sla/resume")
    public R<Void> resumeSla(@AuthenticationPrincipal Caller current, @PathVariable Long id) {
        workOrders.resumeSla(id, current.subjectId());
        return R.ok();
    }

    @Operation(summary = "工单流转留痕", description = "从开单到关闭的每一步，含系统自动转化；线上/线下队列查看人均可读")
    @PreAuthorize("hasAnyAuthority('workorder:online:list','workorder:field:list')")
    @GetMapping("/{id}/timeline")
    public R<List<WorkOrderService.EventView>> timeline(@PathVariable Long id) {
        return R.ok(workOrders.timeline(id));
    }

    @Operation(summary = "父子单关系", description = "详情页双向跳转：父单结论、当前单、派生过的子单")
    @PreAuthorize("hasAnyAuthority('workorder:online:list','workorder:field:list')")
    @GetMapping("/{id}/family")
    public R<WorkOrderService.Family> family(@PathVariable Long id) {
        return R.ok(workOrders.family(id));
    }

    /**
     * 这个格口的证据时间线（19C）：四个采集点各一条，含三值结论与 AI 标签。
     *
     * <p>它存在理由是：“你先 AI 识图打简单标签，再由我判断要不要派人”——没有这一面，
     * 业务员就只能凭一句描述决定跑不跑一趟。
     */
    @Operation(summary = "格口证据时间线", description = "开门/关门/看管检测/代关后四个采集点的观测记录；线上/线下队列查看人均可读")
    @PreAuthorize("hasAnyAuthority('workorder:online:list','workorder:field:list')")
    @GetMapping("/{id}/evidence")
    public R<List<WorkOrderService.EvidenceView>> evidence(@PathVariable Long id) {
        return R.ok(workOrders.evidenceFor(id));
    }

    /**
     * AI 工单简报（第 23 刀）：业务员的 copilot。一次生成三件——事件摘要、处理结论草稿、
     * 相似已结单参考。<b>只给当前业务员看，不写回工单数据</b>（Jira/Zendesk 同款形态）：
     * 草稿由人修改后走 resolve 提交，RESOLVED 仍只能由人判定。
     *
     * <p>与 timeline/evidence 同一权限口径（能看单的人才能要简报）；
     * 无 Idempotent——可反复生成是业界形态，成本靠频控管（工具循环一轮生成三件，比对话贵）。
     */
    @Operation(summary = "AI 工单简报", description = "一次生成三件：事件摘要、处理结论文本草稿、相似已结单参考；仅供当前业务员参考，不写回工单")
    @OperationLog(module = "workorder", operation = "AI工单简报")
    @RateLimit(limit = 6, windowSeconds = 60, message = "简报生成太频繁，请稍后再试")
    @PreAuthorize("hasAnyAuthority('workorder:online:list','workorder:field:list')")
    @PostMapping("/{id}/ai-brief")
    public R<WorkOrderBriefPort.Brief> brief(@AuthenticationPrincipal Caller current,
                                             @PathVariable Long id) {
        return R.ok(briefs.brief(id, current.subjectId()));
    }

    @Operation(summary = "记录电话联系", description = "业务员主动拨打后的结果留痕（系统不强制派电话任务，打不打由业务员判断）；结果必填")
    @OperationLog(module = "workorder", operation = "记录电话联系")
    @Idempotent(key = "#current.subjectId() + ':phone:' + #id", requireKey = false)
    @PreAuthorize("hasAuthority('workorder:resolve')")
    @PostMapping("/{id}/phone-note")
    public R<Void> phoneNote(@AuthenticationPrincipal Caller current, @PathVariable Long id,
                             @Valid @RequestBody PhoneNoteCommand cmd) {
        workOrders.notePhoneCall(id, current.subjectId(), cmd.resultNote());
        return R.ok();
    }

    /** 结果必填：只记"打过电话"不留结果，这条流水没有价值。 */
    public record PhoneNoteCommand(@NotBlank @Size(max = 300) String resultNote) {
    }

    /**
     * requestId 由前端生成：处理反馈会派生子单，重放不能派出第二张。
     * closeReason 可选：不传按 MANUAL；用户确认只能由小程序写，事实恢复要现场事实已解除。
     */
    public record ResolveCommand(@NotBlank @Size(max = 200) String requestId,
                                 @NotBlank @Size(max = 400) String resultNote,
                                 boolean needOffline,
                                 CloseReason closeReason) {
    }
}
