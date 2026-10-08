package com.wherelee.cabinet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 分柜机分布式锁策略下的分配行为（与悲观/CAS 跑同一套基类断言）。
 *
 * <p>除了通用不变量，这里额外盯一件第 10 刀才有的风险：**锁泄漏**。
 * 解锁挂在事务同步回调上，一旦有人改了路由让分配发生在事务之外，
 * 或 afterCompletion 没被触发，柜机锁就会一直握着——下一次请求只能等到 lock-wait 超时。
 * 所以用例跑完直接断言那把锁不在锁定状态。
 *
 * <p>等锁上限在本上下文里放到 5 秒：锁是<b>串行化</b>的，“6 线程 6 口”并不等于无竞争，
 * 第 6 个线程要等前 5 个事务提交完。默认的 800ms 下它会主动放弃（这在生产上是对的），
 * 但本用例要验的是“容量内能全部成功”，所以断言前提必须与配置对得上，不能靠巧合绿。
 */
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
        runConcurrentRequests(THREADS, com.wherelee.cabinet.domain.enums.SizeType.LARGE);

        // 锁 key 与 CabinetLockGuard 一致：cab:alloc:lock:{cabinetNo}
        var lock = redissonClient.getLock("cab:alloc:lock:" + currentCabinetNo());
        assertFalse(lock.isLocked(), "用例结束后柜机锁仍被持有，说明 finally 里的解锁没跑到");
    }
}
