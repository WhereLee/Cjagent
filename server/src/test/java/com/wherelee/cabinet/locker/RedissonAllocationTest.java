package com.wherelee.cabinet.locker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest(properties = {"cabinet.alloc.strategy=redisson", "cabinet.alloc.lock-wait-ms=5000"})
class RedissonAllocationTest extends AbstractAllocationBehaviourTest {

    @Autowired
    private RedissonClient redissonClient;

    @Test
    @DisplayName("生效策略是 redisson，且柜机锁守卫已注册")
    void strategyIsRedisson() {
        assertEquals("redisson", allocator.strategy());
        assertEquals("redisson", facade.activeStrategy());
    }

    @Test
    @DisplayName("并发跑完不泄漏锁：柜机锁必须处于未锁定状态")
    void noLockLeakAfterConcurrentRuns() throws Exception {
        runConcurrentRequests(THREADS, com.wherelee.cabinet.locker.api.SizeType.LARGE);

        var lock = redissonClient.getLock("cab:alloc:lock:" + currentCabinetNo());
        assertFalse(lock.isLocked(), "用例结束后柜机锁仍被持有，说明 finally 里的解锁没跑到");
    }
}
