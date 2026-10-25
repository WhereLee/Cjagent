package com.wherelee.cabinet.locker;

import com.wherelee.cabinet.locker.api.SizeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest(properties = "cabinet.alloc.strategy=pessimistic")
class PessimisticAllocationTest extends AbstractAllocationBehaviourTest {

    @Test
    @DisplayName("生效策略是 pessimistic（写进压测报告，避免事后说不清测了哪个）")
    void strategyIsPessimistic() {
        assertEquals("pessimistic", allocator.strategy());
        assertEquals("pessimistic", facade.activeStrategy());
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
                .filter(r -> r instanceof com.wherelee.cabinet.storage.api.StorageOrderView)
                .map(r -> (com.wherelee.cabinet.storage.api.StorageOrderView) r)
                .toList();
        assertEquals(2, views.size(), "8 个 MEDIUM 全空闲，2 个请求都该成功");
        assertTrue(views.stream().allMatch(v -> "MEDIUM".equals(v.allocatedSize())),
                "有 MEDIUM 可用时不该动用大格口，实际分配：" + views);
    }
}
