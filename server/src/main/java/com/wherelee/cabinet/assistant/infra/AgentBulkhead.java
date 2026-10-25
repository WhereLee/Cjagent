package com.wherelee.cabinet.assistant.infra;

import com.wherelee.cabinet.common.context.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class AgentBulkhead {

    private static final Logger log = LoggerFactory.getLogger(AgentBulkhead.class);

    private final ThreadPoolExecutor pool;

    private final long totalBudgetMs;

    private final Counter refused;

    private final Counter timedOut;

    @Autowired
    public AgentBulkhead(@Value("${cabinet.agent.mimo.timeout-ms}") long modelTimeoutMs,
                         MeterRegistry meterRegistry) {
        this(modelTimeoutMs, meterRegistry, Runtime.getRuntime().availableProcessors() * 2 + 1);
    }

    public AgentBulkhead(long modelTimeoutMs, MeterRegistry meterRegistry, int size) {
        this.pool = new ThreadPoolExecutor(size, size, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(size), new ThreadPoolExecutor.AbortPolicy());
        this.totalBudgetMs = modelTimeoutMs * 2;

        Gauge.builder("cabinet.agent.bulkhead.active", pool, ThreadPoolExecutor::getActiveCount)
                .description("AI 舱壁正在执行的线程数")
                .register(meterRegistry);
        Gauge.builder("cabinet.agent.bulkhead.queued", pool, p -> p.getQueue().size())
                .description("AI 舱壁排队数")
                .register(meterRegistry);
        this.refused = Counter.builder("cabinet.agent.bulkhead.refused")
                .tag("reason", "full").register(meterRegistry);
        this.timedOut = Counter.builder("cabinet.agent.bulkhead.refused")
                .tag("reason", "timeout").register(meterRegistry);

        log.info("AI 舱壁就绪：并发 {}，单次预算 {}ms", size, totalBudgetMs);
    }

    public <T> T call(Callable<T> task) {
        Long tenantId = TenantContext.current();

        Future<T> future;
        try {
            future = pool.submit(() -> TenantContext.callAs(tenantId, task));
        } catch (RejectedExecutionException e) {
            refused.increment();
            throw new AgentBulkheadException("AI 并发已满（容量 " + pool.getMaximumPoolSize() + "）");
        }

        try {
            return future.get(totalBudgetMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            timedOut.increment();
            throw new AgentBulkheadException("AI 超出单次预算 " + totalBudgetMs + "ms");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException("AI 调用失败", cause);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new AgentBulkheadException("AI 调用被中断");
        }
    }

    public boolean saturated() {
        return pool.getActiveCount() >= pool.getMaximumPoolSize()
                && pool.getQueue().remainingCapacity() == 0;
    }

    public static class AgentBulkheadException extends RuntimeException {
        public AgentBulkheadException(String message) {
            super(message);
        }
    }
}
