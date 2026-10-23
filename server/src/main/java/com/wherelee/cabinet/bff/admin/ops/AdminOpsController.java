package com.wherelee.cabinet.bff.admin.ops;

import com.wherelee.cabinet.assistant.api.AiJobPort;
import com.wherelee.cabinet.common.annotation.OperationLog;
import com.wherelee.cabinet.domain.enums.AiJobType;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运维 AI 诊断（第 24 刀）：诊断副驾的端点。
 *
 * <p>调研定的形态原样进契约：AI 只产出"给人看的报告/回答"——工具全只读、
 * 建议是草稿、报告不落库，重启/清理/改配置永远是人按运维手册手动执行。
 * 权限点 ops:diagnose（V24，授给 field_ops）：能看服务器内部状态与能看业务台账
 * 是两种能力，不共用语义。
 *
 * <p>频控比业务端点更紧（diagnose 3/60s）：一次诊断要读健康+两类日志+崩溃残留+台账，
 * 是四个 agent 里单次最贵的一笔。
 */
@Tag(name = "后台-运维诊断", description = "AI 只读诊断：巡检解读、GC/OOM 初判、日志聚类、运维问答；建议为草稿，执行留给人")
@RestController
@RequestMapping("/api/admin/ops")
@Validated
public class AdminOpsController {

    private final AiJobPort jobs;

    public AdminOpsController(AiJobPort jobs) {
        this.jobs = jobs;
    }

    @Operation(summary = "AI 运维诊断", description = "一次生成诊断报告四件：健康摘要/日志发现/崩溃残留/建议清单（草稿，不执行）")
    @OperationLog(module = "ops", operation = "AI运维诊断")
    @RateLimit(limit = 3, windowSeconds = 60, message = "诊断生成太频繁，请稍后再试")
    @PreAuthorize("hasAuthority('ops:diagnose')")
    @PostMapping("/diagnose")
    public R<Long> diagnose(@AuthenticationPrincipal Caller current) {
        // 返回作业号而不是报告：生成一次要十几秒，不能占着请求线程。
        // 诊断没有入参，所以只要上一个还没跑完，再点就会拿到同一个作业号（防双击）
        return R.ok(jobs.submit(AiJobType.DIAGNOSE, null, current.subjectId()));
    }

    @Operation(summary = "运维问答", description = "用自然语言问这台服务的运维问题（如\"最近出过什么故障\"）；只归纳只读工具返回的事实")
    @OperationLog(module = "ops", operation = "AI运维问答")
    @RateLimit(limit = 6, windowSeconds = 60, message = "提问太频繁，请稍后再试")
    @PreAuthorize("hasAuthority('ops:diagnose')")
    @PostMapping("/ask")
    public R<Long> ask(@AuthenticationPrincipal Caller current, @Valid @RequestBody AskCommand cmd) {
        // 问题正文直接作为入参：端口内部会压成请求键（列宽 128 放不下 500 字正文）、原文进载荷
        return R.ok(jobs.submit(AiJobType.ASK, cmd.question(), current.subjectId()));
    }

    /** 问答请求体。 */
    public record AskCommand(
            @NotBlank(message = "问题不能为空") @Size(max = 500, message = "问题最长 500 字") String question) {
    }
}
