package com.wherelee.cabinet.assistant.app;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherelee.cabinet.assistant.api.AiJobPort;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.domain.entity.BizAiJob;
import com.wherelee.cabinet.domain.enums.AiJobStatus;
import com.wherelee.cabinet.domain.enums.AiJobType;
import com.wherelee.cabinet.infrastructure.mapper.BizAiJobMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 作业的提交与查询。
 *
 * <p>提交走 {@code READ_COMMITTED} 的唯一理由是<b>撞唯一索引后要能回读到赢了的那条</b>：
 * 双击的两个请求里，一个插入成功、另一个撞 {@code uk_ai_job_active} 返回 0，
 * 此时它必须能看见对手刚提交的那一行并复用它。MySQL 默认的 REPEATABLE READ 下，
 * 本事务的读视图在第一条语句时就定住了，会读不到 —— 与 {@code WorkOrderIntakeService.open}
 * 同一条理由（那边注释写的是“撞唯一索引后回读能看到赢了的那张”）。
 */
@Service
public class AiJobService implements AiJobPort {

    private final BizAiJobMapper jobs;
    private final ObjectMapper json;

    /** 提交结果分两种：真新建与复用已有作业。两者分开计，才能看出“防双击”到底拦下了多少。 */
    private final Counter created;
    private final Counter reused;

    public AiJobService(BizAiJobMapper jobs, ObjectMapper json, MeterRegistry meterRegistry) {
        this.jobs = jobs;
        this.json = json;
        this.created = Counter.builder("cabinet.ai.job.submit").tag("outcome", "created")
                .register(meterRegistry);
        this.reused = Counter.builder("cabinet.ai.job.submit").tag("outcome", "reused")
                .register(meterRegistry);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Long submit(AiJobType jobType, String input, Long requesterId) {
        Long tenantId = TenantContext.current();

        // 请求键与载荷都从同一个入参推出来，不让调用方各拼各的：
        //   诊断没有入参 → 固定串（上一个没跑完就复用）
        //   问答正文太长（500 字）塞不进 128 宽的唯一索引 → 存摘要，原文进 payload
        //   简报要的就是工单号，它本身就短且唯一
        String requestKey = switch (jobType) {
            case DIAGNOSE -> AiJobKeys.GLOBAL;
            case ASK -> AiJobKeys.of(input);
            case BRIEF -> input;
        };
        String payload = jobType == AiJobType.ASK ? input : null;

        // 先查：双击的第二下在这里就返回了，根本不会走到插入
        Long existing = jobs.findActiveId(tenantId, jobType.name(), requestKey, requesterId);
        if (existing != null) {
            reused.increment();
            return existing;
        }

        Long id = IdWorker.getId();
        // 不靠 affected-rows 判断成败：MySQL 对 INSERT ... ON DUPLICATE KEY UPDATE 的返回值
        // （插入 1 / 更新 2 / 无变化 0）在这里没有一样是“我到底插进去了没有”的可靠依据，
        // 实测据此分流会返回一个表里并不存在的 id。改成插完再查一次，以表里的行为准。
        jobs.append(id, tenantId, jobType.name(), requestKey, requesterId, payload);

        Long winner = jobs.findActiveId(tenantId, jobType.name(), requestKey, requesterId);
        if (winner != null && !winner.equals(id)) {
            // 并发对手先插进去了：复用它，本次没产生新作业
            reused.increment();
            return winner;
        }
        created.increment();
        return id;
    }

    @Override
    public AiJobView view(Long jobId, Long requesterId) {
        // 走 BaseMapper：租户拦截器照常生效，越租直接查不到
        BizAiJob job = jobs.selectById(jobId);
        if (job == null || !job.getRequesterId().equals(requesterId)) {
            // 不是自己的作业，一律当不存在——不区分“没有”和“不是你的”，避免探测
            return null;
        }
        Object parsed = null;
        if (job.getStatus() == AiJobStatus.DONE && job.getResult() != null) {
            parsed = readResult(job);
        }
        return new AiJobView(job.getId(), job.getJobType().name(), job.getStatus().name(), parsed,
                job.getLastError(), job.getCreateTime(), job.getUpdateTime());
    }

    private Object readResult(BizAiJob job) {
        try {
            return json.readValue(job.getResult(), Object.class);
        } catch (Exception e) {
            // 产出是执行器自己序列化的，读不回来说明写入时就坏了：把原文交出去，别让前端拿到空白
            return job.getResult();
        }
    }
}
