package com.wherelee.cabinet.assistant.jobs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherelee.cabinet.assistant.api.OpsDiagnosePort;
import com.wherelee.cabinet.assistant.api.WorkOrderBriefPort;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.domain.entity.BizAiJob;
import com.wherelee.cabinet.infrastructure.mapper.BizAiJobMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

@Component
public class AiJobRunner {

    private static final Logger log = LoggerFactory.getLogger(AiJobRunner.class);

    private static final int MAX_ATTEMPTS = 5;

    private static final int ERROR_MAX_LENGTH = 255;

    private final BizAiJobMapper jobs;
    private final OpsDiagnosePort ops;
    private final WorkOrderBriefPort briefs;
    private final ObjectMapper json;

    private final String instanceId = UUID.randomUUID().toString().substring(0, 8);

    @Value("${cabinet.scheduler.lease-seconds:60}")
    private long leaseSeconds;

    @Value("${cabinet.ai.job.batch-size:10}")
    private int batchSize;

    @Value("${cabinet.ai.job.poll-enabled:true}")
    private boolean pollEnabled;

    private final Counter done;
    private final Counter failed;
    private final Timer queueWait;

    public AiJobRunner(BizAiJobMapper jobs, OpsDiagnosePort ops, WorkOrderBriefPort briefs,
                       ObjectMapper json, MeterRegistry meterRegistry) {
        this.jobs = jobs;
        this.ops = ops;
        this.briefs = briefs;
        this.json = json;
        this.done = Counter.builder("cabinet.ai.job.finished").tag("outcome", "done")
                .register(meterRegistry);
        this.failed = Counter.builder("cabinet.ai.job.finished").tag("outcome", "failed")
                .register(meterRegistry);
        this.queueWait = Timer.builder("cabinet.ai.job.queue.wait")
                .description("从登记到被认领的等待时长")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${cabinet.ai.job.poll-interval-ms:10000}",
            initialDelayString = "${cabinet.ai.job.initial-delay-ms:30000}")
    public void poll() {
        if (!pollEnabled) {
            return;
        }
        try {
            int done = runDue();
            if (done > 0) {
                log.info("AI 作业执行了 {} 个", done);
            }
        } catch (RuntimeException e) {
            log.error("AI 作业扫描失败（下一轮继续）", e);
        }
    }

    public int runDue() {
        int done = 0;
        for (Long id : jobs.findDueIds(batchSize)) {
            if (runOne(id)) {
                done++;
            }
        }
        return done;
    }

    private boolean runOne(Long id) {
        String owner = instanceId + ":" + UUID.randomUUID();
        if (jobs.claim(id, owner, LocalDateTime.now().plusSeconds(leaseSeconds)) != 1) {
            return false;
        }

        BizAiJob job = jobs.selectForWorker(id);
        if (job == null) {
            return false;
        }

        if (job.getCreateTime() != null) {
            queueWait.record(Duration.between(job.getCreateTime(), LocalDateTime.now()));
        }

        try {
            String result = TenantContext.callAs(job.getTenantId(), () -> execute(job));
            jobs.markDone(job.getId(), owner, result);
            done.increment();
            return true;
        } catch (RuntimeException e) {
            log.warn("AI 作业执行失败 id={} type={} attempt={}：{}",
                    job.getId(), job.getJobType(), job.getAttempt(), e.toString());
            failSoft(job, owner, e);
            return false;
        }
    }

    private String execute(BizAiJob job) {
        Object produced = switch (job.getJobType()) {
            case DIAGNOSE -> ops.diagnose();
            case ASK -> ops.ask(job.getPayload());
            case BRIEF -> briefs.brief(Long.valueOf(job.getRequestKey()), job.getRequesterId());
        };
        try {
            return json.writeValueAsString(produced);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("作业产出序列化失败 jobId=" + job.getId(), e);
        }
    }

    private void failSoft(BizAiJob job, String owner, RuntimeException cause) {
        try {
            int attempt = job.getAttempt() == null ? 1 : job.getAttempt();
            String error = trim(cause.getMessage());
            if (attempt >= MAX_ATTEMPTS) {
                jobs.markFailed(job.getId(), owner, error);
                failed.increment();
                log.error("AI 作业判失败（已重试 {} 次）id={} type={}", attempt, job.getId(), job.getJobType());
            } else {
                jobs.markRetry(job.getId(), owner, error);
            }
        } catch (RuntimeException recordFailure) {
            log.error("写回作业失败状态时出错 id={}", job.getId(), recordFailure);
        }
    }

    private static String trim(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= ERROR_MAX_LENGTH ? error : error.substring(0, ERROR_MAX_LENGTH);
    }
}
