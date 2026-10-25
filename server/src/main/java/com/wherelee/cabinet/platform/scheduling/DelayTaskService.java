package com.wherelee.cabinet.platform.scheduling;

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
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DelayTaskService {

    private static final Logger log = LoggerFactory.getLogger(DelayTaskService.class);

    private final BizDelayTaskMapper taskMapper;
    private final TransactionTemplate txTemplate;
    private final MeterRegistry meterRegistry;
    private final org.springframework.beans.factory.ObjectProvider<DelayReminder> reminderProvider;
    private final Map<TaskType, TaskHandler> handlers = new EnumMap<>(TaskType.class);

    private final String instanceId = "worker-" + UUID.randomUUID().toString().substring(0, 8);

    private final AtomicLong tenantCursor = new AtomicLong();

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

    public void schedule(TaskType type, String bizKey, Long tenantId, LocalDateTime fireAt) {
        taskMapper.register(IdWorker.getId(), tenantId, type.name(), bizKey, fireAt);
        DelayReminder reminder = reminderProvider.getIfAvailable();
        if (reminder != null) {
            try {
                reminder.remind(type, bizKey, fireAt);
            } catch (RuntimeException e) {
                log.warn("任务提醒写入失败，回退到轮询触发 mode={} type={} bizKey={}",
                        reminder.mode(), type, bizKey, e);
            }
        }
    }

    public void cancel(TaskType type, String bizKey, Long tenantId) {
        taskMapper.cancel(tenantId, type.name(), bizKey);
    }

    private List<Long> claimFairly(int limit) {
        List<Long> tenants = new ArrayList<>(taskMapper.findDueTenants());
        if (tenants.isEmpty()) {
            return List.of();
        }
        int served = Math.min(limit, tenants.size());
        int start = (int) Math.floorMod(tenantCursor.getAndAdd(served), tenants.size());
        Collections.rotate(tenants, -start);
        int perTenant = Math.max(1, limit / tenants.size());
        List<Long> ids = new ArrayList<>(limit);
        for (Long tenantId : tenants) {
            if (ids.size() >= limit) {
                break;
            }
            ids.addAll(taskMapper.findDueIdsForUpdateSkip(tenantId,
                    Math.min(perTenant, limit - ids.size())));
        }
        for (Long tenantId : tenants) {
            if (ids.size() >= limit) {
                break;
            }
            ids.addAll(taskMapper.findDueIdsForUpdateSkip(tenantId, limit - ids.size()));
        }
        return ids;
    }

    public int runDue() {
        List<BizDelayTask> claimed = txTemplate.execute(status -> {
            List<Long> due = claimFairly(batchSize);
            List<BizDelayTask> mine = new ArrayList<>(due.size());
            LocalDateTime leaseUntil = LocalDateTime.now().plusSeconds(leaseSeconds);
            for (Long id : due) {
                if (taskMapper.claim(id, instanceId, leaseUntil) == 1) {
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

    @Value("${cabinet.door-watch.tick-minutes:5}")
    private long doorWatchTickMinutes;

    private long doorWatchTickSeconds() {
        return doorWatchTickMinutes * 60L;
    }

    private long rearmSeconds(TaskType type) {
        return switch (type) {
            case OVERDUE_PICKUP -> overdueRemindMinutes * 60L;
            case FREE_SET_SYNC -> syncIntervalMinutes * 60L;
            case LEDGER_RECONCILE -> reconcileIntervalMinutes * 60L;
            case COMMAND_SWEEP -> sweepIntervalMinutes * 60L;
            case COMMAND_RESCAN -> commandRescanSeconds;
            case DOOR_NOT_CLOSED -> doorWatchTickSeconds();
            default -> 0L;
        };
    }

    private void execute(TaskHandler handler, BizDelayTask task) {
        boolean again = handler.handle(task);
        if (!handler.transactional()) {
            txTemplate.executeWithoutResult(status -> markSettled(task));
            if (again) {
                rearm(task);
            }
            return;
        }
        markSettled(task);
        if (again && task.getTaskType().recurring()) {
            rearm(task);
        }
    }

    private void markSettled(BizDelayTask task) {
        if (taskMapper.markDone(task.getId(), instanceId) == 0) {
            throw new IllegalStateException("租约已丢失，回滚本次执行 id=" + task.getId());
        }
    }

    private void rearm(BizDelayTask task) {
        taskMapper.register(IdWorker.getId(), task.getTenantId(), task.getTaskType().name(),
                task.getBizKey(), LocalDateTime.now().plusSeconds(rearmSeconds(task.getTaskType())));
    }

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
            log.error("写回任务失败状态时出错 id={}", task.getId(), recordFailure);
        }
    }

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

    public Map<TaskType, TaskHandler> registeredHandlers() {
        return handlers.values().stream().collect(Collectors.toMap(TaskHandler::type, Function.identity()));
    }
}
