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

/**
 * Redis 预占策略下的分配行为。
 *
 * <p>与其他三个策略跑<b>完全相同</b>的不变量断言（继承基类），只有配置不同——
 * 这样四组的差异才只能来自并发控制方式本身，而不是测试写法不同。
 *
 * <p>本类额外补两条只有预占才存在的断言：分配成功时集合与 DB 必须同步减一，
 * 以及集合落后于 DB 时校准必须能按 DB 真相补回。这两条是 {@code SlotAllocator} 契约 ②
 * 要求"绕过事务的写必须自带收敛路径"的取证——基类的不变量只查 DB，
 * 查不到"弹出了却没绑上"（少卖）与"绑上了却没弹出"（超卖）这两种两边脱节。
 */
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

    /**
     * 基类只往 DB 插格口，而预占的准入看的是 Redis 集合——不同步就一个位也分配不出来。
     *
     * <p>必须包租户上下文：校准要按租户读格口表，而主线程上没有上下文（租户守卫会拒查询，
     * 这是基类 {@code countByStatus} 注释里记过的同一个坑）。
     */
    @BeforeEach
    void syncFreeSets() {
        TenantContext.runAs(TENANT, () -> slotPool.syncAllFreeSets(currentCabinetId()));
    }

    /** 柜机 ID 每个用例都不同，但集合键留在 Redis 里会越积越多。 */
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
                new CreateOrderCommand("sync-" + UUID.randomUUID(), currentCabinetNo(), "LARGE", 60)));
        assertNotNull(view.orderNo());

        // 两边各减一。集合少而 DB 没少 = 弹出了没绑上（少卖，白占一个位）；
        // DB 少而集合没少 = 绑上了没弹出（下一个人还能弹到它，那就是超卖的前奏）
        assertEquals(setBefore - 1, preDeduction.freeCount(currentCabinetId(), SizeType.LARGE),
                "分配成功后集合必须少一个成员");
        assertEquals(dbBefore - 1, countByStatus(SlotStatus.FREE),
                "分配成功后 DB 侧必须少一个 FREE 格口");
    }

    @Test
    @DisplayName("集合落后于 DB 时，校准按 DB 真相补回（少卖可收敛）")
    void calibrationConvergesUnderSell() {
        // 人为清空集合，模拟"弹出成功但订单事务回滚"留下的少卖状态：
        // DB 里格口是 FREE，Redis 集合里却没有它
        for (SizeType size : SizeType.values()) {
            redis.delete(freeKey(currentCabinetId(), size));
        }
        assertEquals(0, preDeduction.freeCount(currentCabinetId(), SizeType.LARGE),
                "前置条件：集合应先被清空");

        TenantContext.runAs(TENANT, () -> slotPool.syncAllFreeSets(currentCabinetId()));

        // 夹具刚建、还没人下单，所以 LARGE 全 FREE，数量就是基类声明的容量
        assertEquals(LARGE_SLOTS, preDeduction.freeCount(currentCabinetId(), SizeType.LARGE),
                "校准后集合必须与 DB 的可分配数一致，否则少卖永远收敛不了");
    }

    private static String freeKey(Long cabinetId, SizeType size) {
        return FREE_KEY_PREFIX + cabinetId + ":" + size.name();
    }
}
