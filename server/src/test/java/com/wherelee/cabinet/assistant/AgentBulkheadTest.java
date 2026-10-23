package com.wherelee.cabinet.assistant;

import com.wherelee.cabinet.assistant.infra.AgentBulkhead;
import com.wherelee.cabinet.common.context.TenantContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 舱壁的取证。
 *
 * <p>为什么这几条必需：舱壁只在 {@code cabinet.agent.client=real} 时被用到，而 CI 永远跑桩。
 * 没有这组用例，池满与超预算两条路径在流水线上<b>一次都不会被执行</b>，坏了也没人知道——
 * 而它们恰恰是"AI 慢不拖垮别的端点"这句话的全部实现。
 *
 * <p>不需要 Spring 上下文：直接 {@code new AgentBulkhead(毫秒)} 就能测，
 * 这也让它们跑得足够快（毫秒级），可以进单测套件。
 */
class AgentBulkheadTest {

    /** 每个用例一个干净 registry：指标断言不能被上一个用例的计数污染。 */
    private SimpleMeterRegistry registry;

    @BeforeEach
    void freshRegistry() {
        registry = new SimpleMeterRegistry();
    }

    private AgentBulkhead bulkhead(long modelTimeoutMs) {
        return new AgentBulkhead(modelTimeoutMs, registry);
    }

    /** 固定池大小：池大小按 CPU 算，CI runner 与本机核数不同，用例不能靠“这台机器几个核”成立。 */
    private AgentBulkhead bulkhead(long modelTimeoutMs, int poolSize) {
        return new AgentBulkhead(modelTimeoutMs, registry, poolSize);
    }

    /** 等“真的满了”再断言；用 sleep 猜时序就会时红时绿（本地6/8核 vs CI runner）。 */
    private static boolean awaitSaturated(AgentBulkhead bulkhead, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (bulkhead.saturated()) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    @Test
    @DisplayName("正常执行返回结果，且租户上下文随任务进池")
    void carriesTenantIntoThePool() {
        AgentBulkhead bulkhead = bulkhead(1_000);
        TenantContext.set(9001L);
        try {
            Long seen = bulkhead.call(TenantContext::current);
            assertEquals(9001L, seen, "池化线程上必须看到提交时的租户，否则会越租写库");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    @DisplayName("池满立即拒绝：不排队，交给调用方降级")
    void rejectsWhenFullInsteadOfQueueing() throws Exception {
        int size = 2;   // 池 2 + 队列 2。固定下来，不按 CPU 算
        AgentBulkhead bulkhead = bulkhead(5_000, size);

        CountDownLatch release = new CountDownLatch(1);
        List<Thread> occupants = new ArrayList<>();
        try {
            // 直接用线程占位（不用线程池：池里的线程是逐步创建的，等它填满本身就不确定）
            for (int i = 0; i < size * 2; i++) {
                Thread t = new Thread(() -> {
                    try {
                        bulkhead.call(() -> {
                            release.await();
                            return null;
                        });
                    } catch (RuntimeException ignored) {
                        // 被拒也正常：这个线程只负责占住池或队列的一个位置
                    }
                });
                t.setDaemon(true);
                t.start();
                occupants.add(t);
            }

            assertTrue(awaitSaturated(bulkhead, 10_000), "前提不成立：池与队列没被占满");

            assertThrows(AgentBulkhead.AgentBulkheadException.class,
                    () -> bulkhead.call(() -> "should-not-run"),
                    "池与队列都满时必须当场拒绝——排队等于把“慢”扩散成“更慢”");

            // 舱壁满了必须在监控里看得见：否则“AI 变慢”只会表现为用户投诉
            assertEquals(1.0, registry.get("cabinet.agent.bulkhead.refused")
                    .tag("reason", "full").counter().count(), 0.001,
                    "池满要计一笔，且与超时分开计（一个是根本没开始，一个是跑了一半被砍）");
        } finally {
            release.countDown();
            for (Thread t : occupants) {
                t.join(1_000);
            }
        }
    }

    @Test
    @DisplayName("超出单次预算就取消：预算 = 模型单次超时的两倍")
    void rejectsWhenOverBudget() {
        AgentBulkhead bulkhead = bulkhead(50);   // 预算 100ms
        long started = System.nanoTime();

        assertThrows(AgentBulkhead.AgentBulkheadException.class, () -> bulkhead.call(() -> {
            Thread.sleep(2_000);
            return "late";
        }));

        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        assertTrue(elapsedMs < 1_500, "超预算就该取消，不能等任务自己醒：" + elapsedMs + "ms");
        assertEquals(1.0, registry.get("cabinet.agent.bulkhead.refused")
                .tag("reason", "timeout").counter().count(), 0.001,
                "超时也要计一笔（reason=timeout），与池满分开");
    }

    @Test
    @DisplayName("任务自己的异常原样带出，不被当成舱壁拒绝")
    void propagatesTaskFailure() {
        AgentBulkhead bulkhead = bulkhead(1_000);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> bulkhead.call(() -> {
                    throw new IllegalArgumentException("boom");
                }));

        assertInstanceOf(IllegalArgumentException.class, e.getCause(),
                "下游故障要能看到真因，不能被包装成“AI 并发已满”");
    }
}
