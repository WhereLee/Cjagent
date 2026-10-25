package com.wherelee.cabinet.locker.app;

import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

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
