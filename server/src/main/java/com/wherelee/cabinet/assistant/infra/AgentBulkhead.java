package com.wherelee.cabinet.assistant.infra;

import com.wherelee.cabinet.common.context.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

/**
 * AI 调用的舱壁：独立的有界线程池 + 单次对话总预算。
 *
 * <p>为什么要它：{@code /api/mini/support/chat} 的耗时全花在外部模型上，而它原来直接跑在
 * Tomcat 请求线程里。模型一慢，被占住的是容器线程——拖的不是这一个用户，
 * 是同时进来的所有人，包括根本不碰 AI 的下单与取件。批 6 要的是<b>隔离</b>而不是异步：
 * 对客户端仍然是同步拿结果，只是执行换到自己的池里。
 *
 * <p>两个数都是<b>算出来的，不是拍的</b>：
 * <ul>
 *   <li>并发 = {@code 2 × CPU + 1}：AI 调用是 IO 密集（等外部 API），这是该场景的通用公式；</li>
 *   <li>单次预算 = {@code cabinet.agent.mimo.timeout-ms × 2}：一次对话至少要
 *       “调一次工具 + 出结论”两轮，而那个参数是模型请求的读超时（{@code MimoChatClients} 里
 *       同时设给 connect 与 read），所以两轮就是两倍。</li>
 * </ul>
 * 超过两轮的情况会被预算砍掉、走降级话术——这比让用户干等几分钟更诚实。
 *
 * <p>队列与核心同长、拒绝策略是 Abort：池满<b>立即拒绝而不是无限排队</b>。
 * 排队等于把“慢”扩散成“更慢”：用户已经在等，再排两分钟才告诉他不行，
 * 不如当场给一句“稍后再试”（{@code AgentReply.fallback()}）。
 *
 * <p>池满与超时抛同一种异常：对调用方它们没有区别，都是“这次没成，走降级”。
 */
@Component
public class AgentBulkhead {

    private static final Logger log = LoggerFactory.getLogger(AgentBulkhead.class);

    private final ThreadPoolExecutor pool;

    /** 单次对话总预算。 */
    private final long totalBudgetMs;

    /** 池满被拒的次数。与超时分开：一个是“根本没开始”，一个是“跑了一半被砍”。 */
    private final Counter refused;

    /** 超出单次预算的次数。 */
    private final Counter timedOut;

    public AgentBulkhead(@Value("${cabinet.agent.mimo.timeout-ms}") long modelTimeoutMs,
                         MeterRegistry meterRegistry) {
        int size = Runtime.getRuntime().availableProcessors() * 2 + 1;
        this.pool = new ThreadPoolExecutor(size, size, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(size), new ThreadPoolExecutor.AbortPolicy());
        this.totalBudgetMs = modelTimeoutMs * 2;

        // 三条指标是舱壁的可见面：没有它们，“AI 变慢”只会表现为用户投诉，
        // 运维在监控里看不到池已经满了（池满与超预算都不会让请求变成 5xx，只会降级）
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

    /**
     * 在独立线程里执行，租户上下文随任务带过去。
     *
     * @throws AgentBulkheadException 池已满或超出预算。调用方按<b>降级</b>处理，不要转成 5xx
     */
    public <T> T call(Callable<T> task) {
        // 在提交线程上取快照：目标线程是池化线程，直接读会拿到上一个任务残留的租户，
        // 那正是“给 A 租户的记录写进 B 租户”的经典来源（TenantContext 类注释记过这条）
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
            // 任务自己的异常原样带出：它是编程错误或下游故障，不该被当成“舱壁拒绝”掩盖
            throw new IllegalStateException("AI 调用失败", cause);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new AgentBulkheadException("AI 调用被中断");
        }
    }

    /** 池满与超时统一成这一种：对调用方都是“这次没成，走降级”。 */
    public static class AgentBulkheadException extends RuntimeException {
        public AgentBulkheadException(String message) {
            super(message);
        }
    }
}
