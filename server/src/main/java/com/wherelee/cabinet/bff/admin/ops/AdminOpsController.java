package com.wherelee.cabinet.bff.admin.ops;

import com.wherelee.cabinet.assistant.api.AiJobPort;
import com.wherelee.cabinet.common.annotation.OperationLog;
import com.wherelee.cabinet.common.api.PageQuery;
import com.wherelee.cabinet.common.api.PageResult;
import com.wherelee.cabinet.domain.enums.AiJobType;
import com.wherelee.cabinet.locker.api.DeadLetterPort;
import com.wherelee.cabinet.platform.security.Caller;
import com.wherelee.cabinet.common.annotation.RateLimit;
import com.wherelee.cabinet.common.api.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

@Tag(name = "后台-运维诊断", description = "AI 只读诊断：巡检解读、GC/OOM 初判、日志聚类、运维问答；建议为草稿，执行留给人")
@RestController
@RequestMapping("/api/admin/ops")
@Validated
public class AdminOpsController {

    private final AiJobPort jobs;
    private final DeadLetterPort deadLetters;

    public AdminOpsController(AiJobPort jobs, DeadLetterPort deadLetters) {
        this.jobs = jobs;
        this.deadLetters = deadLetters;
    }

    @Operation(summary = "AI 运维诊断", description = "一次生成诊断报告四件：健康摘要/日志发现/崩溃残留/建议清单（草稿，不执行）")
    @OperationLog(module = "ops", operation = "AI运维诊断")
    @RateLimit(limit = 3, windowSeconds = 60, message = "诊断生成太频繁，请稍后再试")
    @PreAuthorize("hasAuthority('ops:diagnose')")
    @PostMapping("/diagnose")
    public R<Long> diagnose(@AuthenticationPrincipal Caller current) {
        return R.ok(jobs.submit(AiJobType.DIAGNOSE, null, current.subjectId()));
    }

    @Operation(summary = "运维问答", description = "用自然语言问这台服务的运维问题（如\"最近出过什么故障\"）；只归纳只读工具返回的事实")
    @OperationLog(module = "ops", operation = "AI运维问答")
    @RateLimit(limit = 6, windowSeconds = 60, message = "提问太频繁，请稍后再试")
    @PreAuthorize("hasAuthority('ops:diagnose')")
    @PostMapping("/ask")
    public R<Long> ask(@AuthenticationPrincipal Caller current, @Valid @RequestBody AskCommand cmd) {
        return R.ok(jobs.submit(AiJobType.ASK, cmd.question(), current.subjectId()));
    }

    @Operation(summary = "死信列表",
            description = "重试耗尽仍失败的事实。RocketMQ 只留 3 天，这里永久归档；带正文，重放前先看清是什么")
    @PreAuthorize("hasAuthority('ops:diagnose')")
    @GetMapping("/dead-letters")
    public R<PageResult<DeadLetterPort.DeadLetterView>> deadLetters(@Valid PageQuery query,
                                                                   @RequestParam(required = false) String status) {
        return R.ok(deadLetters.list(status, query));
    }

    @Operation(summary = "重放一条死信",
            description = "投回原来的业务 topic；已重放过、或缺少租户信息（坏消息）时返回 false")
    @OperationLog(module = "ops", operation = "重放死信")
    @PreAuthorize("hasAuthority('ops:diagnose')")
    @PostMapping("/dead-letter/{id}/replay")
    public R<Boolean> replayDeadLetter(@PathVariable Long id) {
        return R.ok(deadLetters.replay(id));
    }

    public record AskCommand(
            @NotBlank(message = "问题不能为空") @Size(max = 500, message = "问题最长 500 字") String question) {
    }
}
