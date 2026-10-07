package com.wherelee.cabinet.common.context;

import com.alibaba.ttl.threadpool.TtlExecutors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TenantContextTest {

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("runAs 执行完恢复原值，不是清空")
    void runAsRestoresPrevious() {
        TenantContext.set(1L);
        TenantContext.runAs(2L, () -> assertEquals(2L, TenantContext.current()));
        assertEquals(1L, TenantContext.current(), "嵌套执行结束后必须回到外层租户，直接 clear 会让后续逻辑丢上下文");
    }

    @Test
    @DisplayName("嵌套 runAs 逐层还原")
    void nestedRunAs() {
        TenantContext.set(1L);
        TenantContext.runAs(2L, () -> {
            assertEquals(2L, TenantContext.current());
            TenantContext.runAs(3L, () -> assertEquals(3L, TenantContext.current()));
            assertEquals(2L, TenantContext.current());
        });
        assertEquals(1L, TenantContext.current());
    }

    @Test
    @DisplayName("运行时异常原样上抛（不重新包装），且租户被还原")
    void callAsRestoresOnException() {
        TenantContext.set(1L);
        // 必须保持原类型：上抛 IllegalStateException 包装会让 BizException 的
        // 错误码信息在 GlobalExceptionHandler 里丢失，返回给前端就成了 50000
        assertThrows(IllegalArgumentException.class, () -> TenantContext.runAs(2L, () -> {
            throw new IllegalArgumentException("boom");
        }));
        assertEquals(1L, TenantContext.current());
    }

    @Test
    @DisplayName("受检异常无法直接上抛，包成 IllegalStateException 并还原租户")
    void callAsWrapsCheckedException() {
        TenantContext.set(1L);
        assertThrows(IllegalStateException.class, () ->
                TenantContext.callAs(2L, () -> {
                    throw new java.io.IOException("io");
                }));
        assertEquals(1L, TenantContext.current());
    }

    @Test
    @DisplayName("原先无租户时，runAs 结束后保持无租户（不能残留）")
    void runAsFromEmpty() {
        assertNull(TenantContext.current());
        TenantContext.runAs(7L, () -> assertEquals(7L, TenantContext.current()));
        assertNull(TenantContext.current());
    }

    @Test
    @DisplayName("TTL 包装的线程池能带上提交时的租户")
    void ttlPoolPropagatesTenant() throws Exception {
        ExecutorService raw = java.util.concurrent.Executors.newFixedThreadPool(2);
        ExecutorService ttl = TtlExecutors.getTtlExecutorService(raw);
        try {
            TenantContext.set(42L);
            Future<Long> seen = ttl.submit(TenantContext::current);
            assertEquals(42L, seen.get(5, TimeUnit.SECONDS).longValue(), "TTL 未把租户传到池线程");

            TenantContext.set(43L);
            Future<Long> second = ttl.submit(TenantContext::current);
            assertEquals(43L, second.get(5, TimeUnit.SECONDS).longValue(), "第二次提交应带上新租户，而不是线程上残留的旧值");
        } finally {
            ttl.shutdown();
        }
    }
}
