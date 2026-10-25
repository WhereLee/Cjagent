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

@Service
public class AiJobService implements AiJobPort {

    private final BizAiJobMapper jobs;
    private final ObjectMapper json;

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

        String requestKey = switch (jobType) {
            case DIAGNOSE -> AiJobKeys.GLOBAL;
            case ASK -> AiJobKeys.of(input);
            case BRIEF -> input;
        };
        String payload = jobType == AiJobType.ASK ? input : null;

        Long existing = jobs.findActiveId(tenantId, jobType.name(), requestKey, requesterId);
        if (existing != null) {
            reused.increment();
            return existing;
        }

        Long id = IdWorker.getId();
        jobs.append(id, tenantId, jobType.name(), requestKey, requesterId, payload);

        Long winner = jobs.findActiveId(tenantId, jobType.name(), requestKey, requesterId);
        if (winner != null && !winner.equals(id)) {
            reused.increment();
            return winner;
        }
        created.increment();
        return id;
    }

    @Override
    public AiJobView view(Long jobId, Long requesterId) {
        BizAiJob job = jobs.selectById(jobId);
        if (job == null || !job.getRequesterId().equals(requesterId)) {
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
            return job.getResult();
        }
    }
}
