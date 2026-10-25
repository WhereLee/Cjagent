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

class AgentBulkheadTest {

    private SimpleMeterRegistry registry;

    @BeforeEach
    void freshRegistry() {
        registry = new SimpleMeterRegistry();
    }

    private AgentBulkhead bulkhead(long modelTimeoutMs) {
        return new AgentBulkhead(modelTimeoutMs, registry);
    }

    private AgentBulkhead bulkhead(long modelTimeoutMs, int poolSize) {
        return new AgentBulkhead(modelTimeoutMs, registry, poolSize);
    }

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
        int size = 2;
        AgentBulkhead bulkhead = bulkhead(5_000, size);

        CountDownLatch release = new CountDownLatch(1);
        List<Thread> occupants = new ArrayList<>();
        try {
            for (int i = 0; i < size * 2; i++) {
                Thread t = new Thread(() -> {
                    try {
                        bulkhead.call(() -> {
                            release.await();
                            return null;
                        });
                    } catch (RuntimeException ignored) {
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
        AgentBulkhead bulkhead = bulkhead(50);
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
