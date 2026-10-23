package com.wherelee.cabinet.locker.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.domain.entity.BizOutbox;
import com.wherelee.cabinet.domain.enums.OutboxStatus;
import com.wherelee.cabinet.infrastructure.mapper.BizOutboxMapper;
import com.wherelee.cabinet.locker.api.OutboxPort.OwnerNoticePayload;
import com.wherelee.cabinet.locker.api.OutboxPort.WorkOrderAnomalyPayload;
import com.wherelee.cabinet.notify.api.NoticePort;
import com.wherelee.cabinet.servicedesk.api.WorkOrderPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

/**
 * outbox 中继：把 {@code biz_outbox} 里待投递的事实投出去。
 *
 * <p>为什么要有它：业务侧的扇出（开工单、发通知）原来同步调，下游一抛异常就把上游事务
 * 标成 rollback-only，用户取件/上报跟着失败。改成"业务事务里只写一行本地表"之后，
 * 得有人把这一行投出去——就是这里。
 *
 * <p><b>为什么可以不置"执行中"状态、不加租约</b>：投递是幂等的（工单靠唯一索引、
 * 消息靠 dedup_key），重复投递不会造成第二张单或第二条消息；抢占靠
 * {@code claim} 的条件更新（推远 {@code next_attempt_at}），并发实例拿不到同一条。
 * 进程被 kill 时那条事实会在退避基数一个周期后重新露面，不会永久卡死。
 *
 * <p>退避与判死沿用延迟任务的口径：基数取 {@code cabinet.scheduler.backoff-base-seconds}，
 * 上限与 {@code TaskType.maxAttempts()} 的 default 分支同值（5）。同一类基础设施，
 * 两个数字迟早会漂，所以不另造一套。
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** 与 {@code TaskType.maxAttempts()} 的 default 分支同值：投递失败的重试预算不需要单独一套。 */
    private static final int MAX_ATTEMPTS = 5;

    /** {@code last_error} 列宽 255；超长会被 MySQL 截断或直接报错。 */
    private static final int ERROR_MAX_LENGTH = 255;

    private final BizOutboxMapper outbox;
    private final WorkOrderPort workOrders;
    private final NoticePort notices;
    private final ObjectMapper json;
    private final TransactionTemplate txTemplate;
    private final MeterRegistry meterRegistry;

    /** 退避基数沿用延迟任务的键：同一个调度器，不新造一套节奏。 */
    @Value("${cabinet.scheduler.backoff-base-seconds:30}")
    private long backoffBaseSeconds;

    @Value("${cabinet.outbox.batch-size:50}")
    private int batchSize;

    /**
     * 集成测试里关。它的定时投递会与用例自己的数据打架（用例手动调 {@link #deliverBatch()}）：
     * 后台中继在用例的断言之间把待投递事实投出去，就会多出工单、多出消息，计数对不上。
     * 以前这里是常开的，那正是“单独跑绿、全量偶发红”的一个来源。
     */
    @Value("${cabinet.outbox.poll-enabled:true}")
    private boolean pollEnabled;

    public OutboxRelay(BizOutboxMapper outbox, WorkOrderPort workOrders, NoticePort notices,
                       ObjectMapper json, TransactionTemplate txTemplate, MeterRegistry meterRegistry) {
        this.outbox = outbox;
        this.workOrders = workOrders;
        this.notices = notices;
        this.json = json;
        this.txTemplate = txTemplate;
        this.meterRegistry = meterRegistry;
    }

    @Scheduled(fixedDelayString = "${cabinet.outbox.poll-interval-ms:15000}",
            initialDelayString = "${cabinet.outbox.initial-delay-ms:20000}")
    public void poll() {
        if (!pollEnabled) {
            return;
        }
        try {
            int delivered = deliverBatch();
            if (delivered > 0) {
                log.info("outbox 投递了 {} 条事实", delivered);
            }
        } catch (RuntimeException e) {
            // 调度线程绝不能因为一次异常就停摆：显式吞掉并记录，
            // 让告警落在"投递失败"而不是"调度器静默死亡"
            log.error("outbox 中继本轮失败（下一轮继续）", e);
        }
    }

    /**
     * 把当前所有到期的都投完，返回本轮成功投递的条数。
     *
     * <p><b>为什么要循环而不是只投一批</b>：积压超过一批时，新写进来的行会排在
     * {@code order by next_attempt_at} 的队尾，可能等好几轮才轮到。而事实投递的语义是
     * “越早投越好”——积压本来就不该存在。
     *
     * <p>终止条件不是“投满多少批”，而是<b>“这一批一条都没投成”</b>：
     * 失败的行会被退避推远、被别人抢走的行本实例本来就拿不到，两种情况都会让下一批变小；
     * 一批全部落空就说明该停手了，不会空转。（投成功过就一定还有进展，继续下一批。）
     */
    public int deliverBatch() {
        int delivered = 0;
        while (true) {
            List<Long> due = outbox.findDueIds(batchSize);
            if (due.isEmpty()) {
                return delivered;
            }
            int before = delivered;
            for (Long id : due) {
                if (deliverOne(id)) {
                    delivered++;
                }
            }
            if (delivered == before) {
                return delivered;
            }
        }
    }

    private boolean deliverOne(Long id) {
        // 抢占：推远下次可投时间 + attempt+1。并发实例拿不到同一条。
        // 推远的时长用退避基数：抢到之后若进程被杀，这条事实会在一个基数周期后重新露面
        if (outbox.claim(id, LocalDateTime.now().plusSeconds(backoffBaseSeconds)) != 1) {
            return false;
        }
        BizOutbox row = outbox.selectForWorker(id);
        if (row == null) {
            return false;
        }
        try {
            // 业务副作用仍受租户隔离：跳过租户注入只用于"该投哪一条"，不代表投递时可以越租
            TenantContext.runAs(row.getTenantId(), () -> dispatch(row));
            outbox.markDone(id);
            Counter.builder("cabinet.outbox.delivered").tag("type", row.getEventType().name())
                    .register(meterRegistry).increment();
            return true;
        } catch (RuntimeException e) {
            failSoft(row, e);
            return false;
        }
    }

    /** 按事件类型投递。载荷用同一个 record 反序列化——生产方与消费方共用形状，改字段两边一起变。 */
    private void dispatch(BizOutbox row) {
        switch (row.getEventType()) {
            case WORK_ORDER_ANOMALY -> {
                WorkOrderAnomalyPayload payload = read(row, WorkOrderAnomalyPayload.class);
                workOrders.openForAnomaly(payload.slotId(), payload.anomaly(), payload.reason());
            }
            case OWNER_NOTICE -> {
                OwnerNoticePayload payload = read(row, OwnerNoticePayload.class);
                notices.emit(row.getTenantId(), payload.channel(), payload.kind(), payload.dedupKey(),
                        payload.content(), payload.customerId(), payload.workOrderId(), payload.orderNo());
            }
        }
    }

    private <T> T read(BizOutbox row, Class<T> type) {
        try {
            return json.readValue(row.getPayload(), type);
        } catch (Exception e) {
            // 载荷读不出来说明写入时就是坏数据：让 failSoft 去退避与判死，别在这里吞掉
            throw new IllegalStateException("outbox 载荷无法解析 id=" + row.getId(), e);
        }
    }

    /** 失败后的退避/判死，单独事务：不能被投递失败带走。 */
    private void failSoft(BizOutbox row, RuntimeException cause) {
        try {
            int attempt = row.getAttempt() == null ? 1 : row.getAttempt();
            boolean dead = attempt >= MAX_ATTEMPTS;
            LocalDateTime nextAttempt = LocalDateTime.now().plusSeconds(backoff(attempt));
            txTemplate.executeWithoutResult(status -> outbox.markOutcome(row.getId(),
                    dead ? OutboxStatus.DEAD.name() : OutboxStatus.FAILED.name(),
                    nextAttempt, trim(cause.getMessage())));
            if (dead) {
                Counter.builder("cabinet.outbox.dead").tag("type", row.getEventType().name())
                        .register(meterRegistry).increment();
                log.error("outbox 事实判死需人工处理 id={} type={} dedupKey={} attempt={}",
                        row.getId(), row.getEventType(), row.getDedupKey(), attempt);
            }
        } catch (RuntimeException recordFailure) {
            // 记录失败本身不能再抛出：那条事实会在退避基数一个周期后重新露面，比吞掉异常好
            log.error("写回 outbox 失败状态时出错 id={}", row.getId(), recordFailure);
        }
    }

    /** 指数退避：瞬时故障越试越疏，避免坏数据以轮询频率打爆日志与下游。 */
    private long backoff(int attempt) {
        int exp = Math.min(attempt, 6);
        return backoffBaseSeconds * (1L << exp);
    }

    private static String trim(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= ERROR_MAX_LENGTH ? error : error.substring(0, ERROR_MAX_LENGTH);
    }
}
