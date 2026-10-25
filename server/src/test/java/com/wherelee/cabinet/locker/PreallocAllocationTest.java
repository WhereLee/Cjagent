package com.wherelee.cabinet.locker;

import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.locker.api.SizeType;
import com.wherelee.cabinet.locker.api.SlotPoolPort;
import com.wherelee.cabinet.locker.api.SlotStatus;
import com.wherelee.cabinet.locker.infra.SlotPreDeductionService;
import com.wherelee.cabinet.storage.api.CreateOrderCommand;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest(properties = "cabinet.alloc.strategy=prealloc")
class PreallocAllocationTest extends AbstractAllocationBehaviourTest {

    private static final String FREE_KEY_PREFIX = "cab:alloc:free:";

    @Autowired
    private SlotPoolPort slotPool;
    @Autowired
    private SlotPreDeductionService preDeduction;
    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void syncFreeSets() {
        TenantContext.runAs(TENANT, () -> slotPool.syncAllFreeSets(currentCabinetId()));
    }

    @AfterEach
    void clearFreeSets() {
        Long cabinetId = currentCabinetId();
        if (cabinetId == null) {
            return;
        }
        for (SizeType size : SizeType.values()) {
            redis.delete(freeKey(cabinetId, size));
        }
    }

    @Test
    @DisplayName("生效策略是 prealloc")
    void strategyIsPrealloc() {
        assertEquals("prealloc", allocator.strategy());
        assertEquals("prealloc", facade.activeStrategy());
    }

    @Test
    @DisplayName("分配成功后集合与 DB 同步减一：弹出与绑定不许脱节")
    void popAndReserveStayInSync() {
        long setBefore = preDeduction.freeCount(currentCabinetId(), SizeType.LARGE);
        int dbBefore = countByStatus(SlotStatus.FREE);
        assertEquals(LARGE_SLOTS, setBefore, "前置条件：集合里应有全部大格口");

        fund(900888L);
        var view = TenantContext.callAs(TENANT, () -> storageOrderService.create(900888L,
                new CreateOrderCommand("sync-" + UUID.randomUUID(), currentCabinetNo(), "LARGE")));
        assertNotNull(view.orderNo());

        assertEquals(setBefore - 1, preDeduction.freeCount(currentCabinetId(), SizeType.LARGE),
                "分配成功后集合必须少一个成员");
        assertEquals(dbBefore - 1, countByStatus(SlotStatus.FREE),
                "分配成功后 DB 侧必须少一个 FREE 格口");
    }

    @Test
    @DisplayName("集合落后于 DB 时，校准按 DB 真相补回（少卖可收敛）")
    void calibrationConvergesUnderSell() {
        for (SizeType size : SizeType.values()) {
            redis.delete(freeKey(currentCabinetId(), size));
        }
        assertEquals(0, preDeduction.freeCount(currentCabinetId(), SizeType.LARGE),
                "前置条件：集合应先被清空");

        TenantContext.runAs(TENANT, () -> slotPool.syncAllFreeSets(currentCabinetId()));

        assertEquals(LARGE_SLOTS, preDeduction.freeCount(currentCabinetId(), SizeType.LARGE),
                "校准后集合必须与 DB 的可分配数一致，否则少卖永远收敛不了");
    }

    private static String freeKey(Long cabinetId, SizeType size) {
        return FREE_KEY_PREFIX + cabinetId + ":" + size.name();
    }
}
