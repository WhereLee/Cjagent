package com.wherelee.cabinet.locker;

import com.wherelee.cabinet.locker.app.CasSlotAllocator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest(properties = "cabinet.alloc.strategy=cas")
class CasAllocationTest extends AbstractAllocationBehaviourTest {

    @Test
    @DisplayName("生效策略是 cas")
    void strategyIsCas() {
        assertEquals("cas", allocator.strategy());
        assertEquals("cas", facade.activeStrategy());
    }

    @Test
    @DisplayName("CAS 会真的发生竞争重试（平均尝试次数 > 1），说明确实在抢而非排队")
    void contentionProducesRetries() throws Exception {
        var results = runConcurrentRequests(THREADS, com.wherelee.cabinet.locker.api.SizeType.LARGE);

        var views = results.stream()
                .filter(r -> r instanceof com.wherelee.cabinet.storage.api.StorageOrderView)
                .map(r -> (com.wherelee.cabinet.storage.api.StorageOrderView) r)
                .toList();
        assertEquals(LARGE_SLOTS, views.size());

        double avgTried = views.stream().mapToInt(
                com.wherelee.cabinet.storage.api.StorageOrderView::triedSlots).average().orElse(0);
        Assertions.assertAll(
                () -> assertTrue(avgTried > 1.0, "CAS 平均尝试次数应大于 1，实际 " + avgTried),
                () -> assertTrue(views.stream().allMatch(v -> v.triedSlots() <= CasSlotAllocator.MAX_ATTEMPTS),
                        "单次请求尝试数不能超过止损上限" + CasSlotAllocator.MAX_ATTEMPTS));
    }
}
