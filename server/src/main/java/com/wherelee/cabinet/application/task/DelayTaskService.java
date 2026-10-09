package com.wherelee.cabinet.application.task;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.domain.entity.BizDelayTask;
import com.wherelee.cabinet.domain.enums.TaskStatus;
import com.wherelee.cabinet.domain.enums.TaskType;
import com.wherelee.cabinet.infrastructure.mapper.BizDelayTaskMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 延迟任务的登记、抢占与执行。<b>三种触发方式都汇聚到这里，正确性只由这张表保证。</b>
 *
 * <p>为什么这样分层：MQ 定时消息、Redis ZSet、DB 轮询各有短板（MQ 粒度粗、ZSet 会漂移、轮询有延迟），
 * 但只要"能不能执行"是一次条件更新，三者都可以同时存在、互为备份，<b>而不会让同一个任务跑两遍</b>。
 * 这是第 9~10 刀反复出现的结论的调度版：<b>跨存储没有事务，所以真相源只能有一个。</b>
 *
 * <p>三个事务边界是刻意的：
 * <ol>
 *   <li>抢占批：一个短事务里 {@code FOR UPDATE SKIP LOCKED} 选候选 + 条件更新抢租约后提交；</li>
 *   <li>执行 + {@code markDone}：<b>同一个事务</b>——业务成功而状态没落成 DONE，等于让下一个持有者重跑；
 *       状态落成了而业务回滚，等于任务被吞掉；</li>
 *   <li>失败记录：<b>另起一个事务</b>（{@code REQUIRES_NEW} 语义）。如果和业务共用事务，
 *       回滚会把 attempt 与退避时间一起抹掉，指数退避形同不存在，坏数据会以轮询频率打爆日志。</li>
 * </ol>
 */
@Service
public class DelayTaskService {

    private static final Logger log = LoggerFactory.getLogger(DelayTaskService.class);

    private final BizDelayTaskMapper taskMapper;
    private final TransactionTemplate txTemplate;
    private final MeterRegistry meterRegistry;
    /** 提醒可以是 no-op（只靠轮询）：不能因为没接 Redis/MQ 就起不来。 */
    private final org.springframework.beans.factory.ObjectProvider<DelayReminder> reminderProvider;
    private final Map<TaskType, TaskHandler> handlers = new EnumMap<>(TaskType.class);

    /** 进程内唯一，重启会变——租约判断靠"是否本人持有"而不是"是不是同一进程"。 */
    private final String instanceId = "worker-" + UUID.randomUUID().toString().substring(0, 8);

    @Value("${cabinet.scheduler.batch-size:20}")
    private int batchSize;

    @Value("${cabinet.scheduler.lease-seconds:60}")
    private long leaseSeconds;

    @Value("${cabinet.scheduler.backoff-base-seconds:30}")
    private long backoffBaseSeconds;

    @Value("${cabinet.scheduler.overdue-remind-minutes:60}")
    private long overdueRemindMinutes;

    @Value("${cabinet.scheduler.sync-interval-minutes:10}")
    private long syncIntervalMinutes;

    @Value("${cabinet.scheduler.reconcile-interval-minutes:30}")
    private long reconcileIntervalMinutes;

    @Value("${cabinet.scheduler.command-rescan-seconds:30}")
    private long commandRescanSeconds;

    @Value("${cabinet.scheduler.sweep-interval-minutes:5}")
    private long sweepIntervalMinutes;

    private Timer runTimer;

    public DelayTaskService(BizDelayTaskMapper taskMapper,
                            TransactionTemplate txTemplate,
                            MeterRegistry meterRegistry,
                            org.springframework.beans.factory.ObjectProvider<DelayReminder> reminderProvider,
                            List<TaskHandler> handlerBeans) {
        this.taskMapper = taskMapper;
        this.txTemplate = txTemplate;
        this.meterRegistry = meterRegistry;
        this.reminderProvider = reminderProvider;
        for (TaskHandler handler : handlerBeans) {
            TaskHandler previous = handlers.put(handler.type(), handler);
            if (previous != null) {
                // 两个 handler 抢同一个类型会被静默覆盖，表现为"其中一个类型永远不会被处理"
                throw new IllegalStateException("TaskHandler 类型重复: " + handler.type());
            }
        }
    }

    @PostConstruct
    void bindMetrics() {
        runTimer = Timer.builder("cabinet.task.run").description("延迟任务执行耗时").register(meterRegistry);
        Gauge.builder("cabinet.task.backlog", taskMapper, m -> m.countDueBacklog())
                .description("一小时内到期仍未完成的任务数").register(meterRegistry);
        for (TaskType type : TaskType.values()) {
            if (!handlers.containsKey(type)) {
                log.warn("任务类型 {} 没有 handler，登记了也不会执行", type);
            }
        }
    }

    /**
     * 登记任务。<b>fire_at 只能提前不能推后</b>（见 Mapper 的 ON DUPLICATE 规则）：
     * 否则同一订单反复登记会把超时时刻无限推迟，等于永远不会触发。
     */
    public void schedule(TaskType type, String bizKey, Long tenantId, LocalDateTime fireAt) {
        taskMapper.register(IdWorker.getId(), tenantId, type.name(), bizKey, fireAt);
        DelayReminder reminder = reminderProvider.getIfAvailable();
        if (reminder != null) {
            try {
                reminder.remind(type, bizKey, fireAt);
            } catch (RuntimeException e) {
                // 提醒失败不影响正确性（轮询会扫到），只影响延迟——因此绝不能让下单因为 Redis 挂掉而失败
                log.warn("任务提醒写入失败，回退到轮询触发 mode={} type={} bizKey={}",
                        reminder.mode(), type, bizKey, e);
            }
        }
    }

    /**
     * 抢一批到期的任务并执行。
     *
     * @return 实际执行的任务数（0 表示没有到期任务或被别人抢光了）
     */
    public int runDue() {
        List<BizDelayTask> claimed = txTemplate.execute(status -> {
            List<Long> due = taskMapper.findDueIdsForUpdateSkip(batchSize);
            List<BizDelayTask> mine = new ArrayList<>(due.size());
            LocalDateTime leaseUntil = LocalDateTime.now().plusSeconds(leaseSeconds);
            for (Long id : due) {
                if (taskMapper.claim(id, instanceId, leaseUntil) == 1) {
                    // 抢完再读，所以 attempt 已经包涵本轮；取行必须走跳租户的专用查询
                    // （调度线程没有租户上下文，用 selectById 会被守卫拒执，任务一个也跑不了）
                    BizDelayTask task = taskMapper.selectForWorker(id);
                    if (task != null) {
                        mine.add(task);
                    }
                }
            }
            return mine;
        });
        if (claimed == null || claimed.isEmpty()) {
            return 0;
        }
        for (BizDelayTask task : claimed) {
            runOne(task);
        }
        return claimed.size();
    }

    private void runOne(BizDelayTask task) {
        String previousTrace = MDC.get("traceId");
        MDC.put("traceId", "task-" + task.getTaskType().name().toLowerCase() + "-" + task.getId());
        long started = System.nanoTime();
        try {
            TaskHandler handler = handlers.get(task.getTaskType());
            if (handler == null) {
                record(task, "nohandler");
                failSoft(task, "没有注册 handler：" + task.getTaskType());
                return;
            }
            if (handler.transactional()) {
                txTemplate.executeWithoutResult(status -> TenantContext.runAs(task.getTenantId(),
                        () -> execute(handler, task)));
            } else {
                // 等外部 I/O 的 handler 不能被外层事务包住：那会把“等回执不持事务”重新合上。
                // 代价：业务与 markDone 不再原子，所以这类 handler 必须完全可重入。
                TenantContext.runAs(task.getTenantId(), () -> execute(handler, task));
            }
            record(task, "done");
        } catch (Exception e) {
            log.error("任务执行失败 type={} bizKey={} attempt={}",
                    task.getTaskType(), task.getBizKey(), task.getAttempt(), e);
            record(task, "retry");
            failSoft(task, e.getMessage());
        } finally {
            runTimer.record(Duration.ofNanos(System.nanoTime() - started));
            if (previousTrace != null) {
                MDC.put("traceId", previousTrace);
            } else {
                MDC.remove("traceId");
            }
        }
    }

    /** 续排间隔：不继承上一次 fire_at，避免“越跑越早”的漂移（执行慢了就把下一轮拉得更近）。 */
    private long rearmSeconds(TaskType type) {
        return switch (type) {
            case OVERDUE_PICKUP -> overdueRemindMinutes * 60L;
            case FREE_SET_SYNC -> syncIntervalMinutes * 60L;
            case LEDGER_RECONCILE -> reconcileIntervalMinutes * 60L;
            case COMMAND_SWEEP -> sweepIntervalMinutes * 60L;
            // 回扫的下一轮就是下一次重试的间隔：与首次登记用同一个参数，
            // 否则“首次 30 秒、重试 5 分钟”这种不人能解释的节奏就会出现在同一条指令上
            case COMMAND_RESCAN -> commandRescanSeconds;
            default -> 0L;
        };
    }

    /** 执行主体：跑 handler、落 DONE、按需续排。事务与非事务两条路径共用这一段。 */
    private void execute(TaskHandler handler, BizDelayTask task) {
        boolean again = handler.handle(task);
        if (!handler.transactional()) {
            // 非事务 handler 的 markDone 自己成一个短事务
            txTemplate.executeWithoutResult(status -> markSettled(task));
            if (again) {
                rearm(task);
            }
            return;
        }
        markSettled(task);
        if (again && task.getTaskType().recurring()) {
            // 续排与 markDone 同事务：否则“排上了但状态没落”会让下一轮重复执行同一个任务
            rearm(task);
        }
    }

    private void markSettled(BizDelayTask task) {
        if (taskMapper.markDone(task.getId(), instanceId) == 0) {
            // 业务写完了，但状态落不下去（租约被接管）——必须回滚业务，否则会双执行
            throw new IllegalStateException("租约已丢失，回滚本次执行 id=" + task.getId());
        }
    }

    private void rearm(BizDelayTask task) {
        taskMapper.register(IdWorker.getId(), task.getTenantId(), task.getTaskType().name(),
                task.getBizKey(), LocalDateTime.now().plusSeconds(rearmSeconds(task.getTaskType())));
    }

    /** 失败后的退避/判死，单独事务：不能被业务回滚带走。 */
    private void failSoft(BizDelayTask task, String error) {
        try {
            int attempt = task.getAttempt() == null ? 1 : task.getAttempt();
            int max = task.getTaskType().maxAttempts();
            boolean dead = attempt >= max;
            LocalDateTime nextFire = LocalDateTime.now().plusSeconds(backoff(task, attempt));
            txTemplate.executeWithoutResult(status -> taskMapper.markOutcome(task.getId(), instanceId,
                    dead ? TaskStatus.DEAD.name() : TaskStatus.FAILED.name(),
                    nextFire, trim(error)));
            if (dead) {
                Counter.builder("cabinet.task.dead").tag("type", task.getTaskType().name())
                        .register(meterRegistry).increment();
                log.error("任务判死需人工处理 id={} type={} bizKey={} attempt={}",
                        task.getId(), task.getTaskType(), task.getBizKey(), attempt);
            }
        } catch (Exception recordFailure) {
            // 记录失败本身不能再抛出：租约到期后会被重新抢占，比吞掉异常好
            log.error("写回任务失败状态时出错 id={}", task.getId(), recordFailure);
        }
    }

    /** 指数退避：瞬时故障越试越疏，避免坏数据以轮询频率打爆日志与下游。 */
    private long backoff(BizDelayTask task, int attempt) {
        int exp = Math.min(attempt, 6);
        return backoffBaseSeconds * (1L << exp);
    }

    private void record(BizDelayTask task, String outcome) {
        Counter.builder("cabinet.task.executed")
                .tag("type", task.getTaskType().name())
                .tag("outcome", outcome)
                .register(meterRegistry).increment();
    }

    private String trim(String value) {
        if (value == null) {
            return "unknown";
        }
        return value.length() <= 255 ? value : value.substring(0, 255);
    }

    public String instanceId() {
        return instanceId;
    }

    /** 供触发器与测试：已注册的 handler 类型集合。 */
    public Map<TaskType, TaskHandler> registeredHandlers() {
        return handlers.values().stream().collect(Collectors.toMap(TaskHandler::type, Function.identity()));
    }
}
