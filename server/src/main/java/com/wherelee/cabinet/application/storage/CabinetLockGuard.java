package com.wherelee.cabinet.application.storage;

import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 柜机级分布式锁守卫：把整个用例（含事务）包在锁里。
 *
 * <p><b>为什么锁必须在事务之外——这是第 10 刀用失败测试换来的结论。</b>
 * 我最初把加锁放在分配器里（事务内部），并发用例仍然报"格口刚刚被占用"：
 * MySQL REPEATABLE READ 的一致性快照在<b>事务的第一次读</b>时就建立了，
 * 而那次读发生在拿锁之前（查柜机）。于是线程 B 拿到锁后读候选，看到的仍是 A 提交前的旧快照，
 * 两人选中同一个格口——<b>锁只挡住了写入，挡不住旧快照的读取</b>。
 *
 * <p>三条出路，本项目选第一条：
 * <ol>
 *   <li>锁在事务外（本类）：进入事务时快照必然已经能看到前一个事务的提交 ✓</li>
 *   <li>锁内改用 {@code SELECT … FOR UPDATE}（当前读，绕过快照）：就是 pessimistic 策略，
 *       那就不需要分布式锁了；</li>
 *   <li>把隔离级别降到 READ COMMITTED：影响面太大，不为一个用例动全局。</li>
 * </ol>
 *
 * <p>锁的粒度和生命周期：一台柜机一把锁（按 cabinetNo，它在命令里就有，不必先查库换算 ID），
 * 等锁有上限（超时就回 10410 让客户端重试或换柜机，绝不无限排队），
 * 解锁在 finally 里且只解自己持有的——事务失败也不影响，因为这里没有事务同步的参与。
 */
@Component
@ConditionalOnProperty(name = "cabinet.alloc.strategy", havingValue = "redisson")
public class CabinetLockGuard {

    private static final String LOCK_PREFIX = "cab:alloc:lock:";

    private final RedissonClient redissonClient;

    @Value("${cabinet.alloc.lock-wait-ms:800}")
    private long lockWaitMs;

    public CabinetLockGuard(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /** 该策略下需要包锁的用例数（facade 用它决定要不要走锁）。 */
    public boolean active() {
        return true;
    }

    public <T> T aroundCabinet(String cabinetNo, Supplier<T> action) {
        RLock lock = redissonClient.getLock(LOCK_PREFIX + cabinetNo);

        boolean locked;
        try {
            locked = lock.tryLock(lockWaitMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ResultCode.SYSTEM_ERROR, "等待柜机锁时被中断，请重试");
        }
        if (!locked) {
            // 拿不到锁是"这台柜机正忙"，不是"没位"：回 10410 才能被客户端正确处置（重试/换柜机）
            throw new BizException(ResultCode.SLOT_RACE_LOST, "该柜机正忙，请重试或换一台");
        }

        try {
            return action.get();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
