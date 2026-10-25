package com.wherelee.cabinet.locker.jobs;

import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.domain.entity.BizOutbox;
import com.wherelee.cabinet.domain.enums.OutboxStatus;
import com.wherelee.cabinet.infrastructure.mapper.BizOutboxMapper;
import com.wherelee.cabinet.locker.api.OutboxPublisher;
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

@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private static final int MAX_ATTEMPTS = 5;

    private static final int ERROR_MAX_LENGTH = 255;

    private final BizOutboxMapper outbox;
    private final OutboxPublisher publisher;
    private final TransactionTemplate txTemplate;
    private final MeterRegistry meterRegistry;

    @Value("${cabinet.scheduler.backoff-base-seconds:30}")
    private long backoffBaseSeconds;

    @Value("${cabinet.outbox.batch-size:50}")
    private int batchSize;

    @Value("${cabinet.outbox.poll-enabled:true}")
    private boolean pollEnabled;

    public OutboxRelay(BizOutboxMapper outbox, OutboxPublisher publisher,
                       TransactionTemplate txTemplate, MeterRegistry meterRegistry) {
        this.outbox = outbox;
        this.publisher = publisher;
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
            log.error("outbox 中继本轮失败（下一轮继续）", e);
        }
    }

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
        if (outbox.claim(id, LocalDateTime.now().plusSeconds(backoffBaseSeconds)) != 1) {
            return false;
        }
        BizOutbox row = outbox.selectForWorker(id);
        if (row == null) {
            return false;
        }
        try {
            TenantContext.runAs(row.getTenantId(), () -> publisher.publish(row));
            outbox.markDone(id);
            Counter.builder("cabinet.outbox.delivered").tag("type", row.getEventType().name())
                    .register(meterRegistry).increment();
            return true;
        } catch (RuntimeException e) {
            failSoft(row, e);
            return false;
        }
    }

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
            log.error("写回 outbox 失败状态时出错 id={}", row.getId(), recordFailure);
        }
    }

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
