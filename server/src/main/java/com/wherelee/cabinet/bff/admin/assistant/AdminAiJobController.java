package com.wherelee.cabinet.bff.admin.assistant;

import com.wherelee.cabinet.assistant.api.AiJobPort;
import com.wherelee.cabinet.common.api.R;
import com.wherelee.cabinet.platform.security.Caller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 作业查询（批 7）。
 *
 * <p>三个 AI 端点（运维诊断、运维问答、工单简报）改成作业式之后，请求线程只登记并返回作业号，
 * 产出由 {@code AiJobRunner} 在后台写。这个端点就是前端拿作业号来读产出的那一环。
 *
 * <p><b>只返回提交人自己的作业</b>：AI 产出是 copilot 形态、仅提交人可见。
 * 别人的作业一律当不存在（不区分“没有”与“不是你的”，避免拿作业号探测）。
 * 那条校验在 {@code AiJobService.view} 里，不靠这里再写一遍。
 *
 * <p>没有单独的权限点：能提交那三个端点的人本来就有各自的权限（{@code ops:diagnose}、
 * 工单 list），而这里再叠一层只会出现“有权限提交、没权限看结果”的荒谬情形。
 */
@Tag(name = "后台-AI 作业", description = "AI 长任务改成作业式：提交拿作业号，前端按作业号轮询产出")
@RestController
@RequestMapping("/api/admin/ai-jobs")
public class AdminAiJobController {

    private final AiJobPort jobs;

    public AdminAiJobController(AiJobPort jobs) {
        this.jobs = jobs;
    }

    @Operation(summary = "查 AI 作业",
            description = "SUBMITTED/RUNNING 时继续轮询；DONE 读 result；FAILED 读 error。"
                    + "别人的作业返回 data=null（当不存在）")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/{id}")
    public R<AiJobPort.AiJobView> view(@AuthenticationPrincipal Caller current, @PathVariable Long id) {
        return R.ok(jobs.view(id, current.subjectId()));
    }
}
