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

/**
 * AI 作业执行器：扫表认领 → 调对应端口 → 写产出。
 *
 * <p><b>为什么主路径是扫表而不是只靠 MQ</b>：MQ 消息丢了、消费者挂了、broker 没起，
 * 作业都会永久卡在 SUBMITTED 而没人知道。表才是真相源，MQ 最多是“立刻开工”的唤醒——
 * 这与 {@code biz_delay_task} 那句“三种触发方式都只决定什么时候去看一眼，
 * 谁真正执行由表上的条件更新决定”是同一条哲学。
 *
 * <p>租约沿用 {@code cabinet.scheduler.lease-seconds}：语义完全一样（持有者多久算死了），
 * 不新造一个数。判死上限 5 也与 {@code TaskType.maxAttempts()} 的 default 与
 * {@code OutboxRelay} 同值。
 *
 * <p><b>宁可重复执行，不可漏执行</b>——这句话原在已删除的 {@code ConsumeIdempotencyGuard} 里
 * （批 8 判定它零调用方）。认领靠条件更新而不是“先查后跑”，所以两个实例不会同时跑同一作业；
 * 即使真被重复跑，三类作业的动作都只读（诊断读日志与台账、问答检索只读事实、简报归纳工单），
 * 重复的代价远小于漏执行的代价。
 */
@Component
public class AiJobRunner {

    private static final Logger log = LoggerFactory.getLogger(AiJobRunner.class);

    /** 与 TaskType.maxAttempts() 的 default、OutboxRelay 同值：同一类基础设施用一个重试预算。 */
    private static final int MAX_ATTEMPTS = 5;

    /** {@code last_error} 列宽 255。 */
    private static final int ERROR_MAX_LENGTH = 255;

    private final BizAiJobMapper jobs;
    private final OpsDiagnosePort ops;
    private final WorkOrderBriefPort briefs;
    private final ObjectMapper json;

    /** 持有者标识：实例随机串 + 每次认领再拼一个随机串，避免同实例的两个线程互相覆盖租约。 */
    private final String instanceId = UUID.randomUUID().toString().substring(0, 8);

    @Value("${cabinet.scheduler.lease-seconds:60}")
    private long leaseSeconds;

    @Value("${cabinet.ai.job.batch-size:10}")
    private int batchSize;

    /**
     * 集成测试里关。后台扫描会与用例自己的数据打架（它们自己调 {@link #runDue()}）：
     * 用例刚插入的作业可能被定时任务在执行前就跑掉，断言就看不到 SUBMITTED 了。
     * 不能靠 {@code @ConditionalOnProperty} 关：那样 bean 不存在，测试也注入不了。
     */
    @Value("${cabinet.ai.job.poll-enabled:true}")
    private boolean pollEnabled;

    /** 完成/失败分开计；排队时长单独一个 Timer——它是“作业系统健不健康”最直接的读数。 */
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
            // 调度线程不能因为一次异常停摆：显式吞掉并记录，让告警落在“作业失败”而不是“调度器静默死亡”
            log.error("AI 作业扫描失败（下一轮继续）", e);
        }
    }

    /** 供 MQ 唤醒与测试直接调用。返回本轮成功执行的个数。 */
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
            // 被别人抢走了（或刚有人把它推成终态）：本轮不管它
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
            // 业务副作用在作业所属租户里执行：跳过注入只用于“该跑哪一条”，不代表执行时可以越租
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

    /** 按作业类型调对应端口。三种产出都是 record，序列化成 JSON 存 result 列，前端直接读。 */
    private String execute(BizAiJob job) {
        Object produced = switch (job.getJobType()) {
            case DIAGNOSE -> ops.diagnose();
            case ASK -> ops.ask(job.getPayload());
            case BRIEF -> briefs.brief(Long.valueOf(job.getRequestKey()), job.getRequesterId());
        };
        try {
            return json.writeValueAsString(produced);
        } catch (JsonProcessingException e) {
            // 产出是 record，序列化不会失败；走到这里是编程错误，抛出去让 failSoft 记下原因
            throw new IllegalStateException("作业产出序列化失败 jobId=" + job.getId(), e);
        }
    }

    /** 失败处置：还有预算就回 SUBMITTED 等下一轮，用尽则 FAILED 并释放 active_flag。 */
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
            // 记录失败本身不能再抛出：租约到期后会被重新认领，比吞掉异常好
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
