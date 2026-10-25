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
