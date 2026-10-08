package com.wherelee.cabinet;

import com.wherelee.cabinet.domain.enums.SizeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 悲观锁策略下的分配行为（默认策略）。
 *
 * <p>压测时这一份的表现是"吞吐被串行化"：同一柜机的空闲格口逐个加锁，
 * 20 个线程排队通过。功能上没问题，性能差异留到 JMeter 报告里量化。
 */
@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest(properties = "cabinet.alloc.strategy=pessimistic")
class PessimisticAllocationTest extends AbstractAllocationBehaviourTest {

    @Test
    @DisplayName("生效策略是 pessimistic（写进压测报告，避免事后说不清测了哪个）")
    void strategyIsPessimistic() {
        assertEquals("pessimistic", allocator.strategy());
        assertEquals("pessimistic", storageOrderService.activeStrategy());
    }

    @Test
    @DisplayName("尺寸偏好：MEDIUM 请求优先用 MEDIUM，不去碰更稀缺的 LARGE")
    void prefersSmallestFittingSize() {
        List<Object> results = null;
        try {
            results = runConcurrentRequests(2, SizeType.MEDIUM);
        } catch (Exception e) {
            throw new AssertionError("并发请求异常", e);
        }

        var views = results.stream()
                .filter(r -> r instanceof com.wherelee.cabinet.application.storage.dto.StorageOrderView)
                .map(r -> (com.wherelee.cabinet.application.storage.dto.StorageOrderView) r)
                .toList();
        assertEquals(2, views.size(), "8 个 MEDIUM 全空闲，2 个请求都该成功");
        assertTrue(views.stream().allMatch(v -> "MEDIUM".equals(v.allocatedSize())),
                "有 MEDIUM 可用时不该动用大格口，实际分配：" + views);
    }
}
