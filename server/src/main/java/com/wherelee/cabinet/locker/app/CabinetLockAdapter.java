package com.wherelee.cabinet.locker.app;

import com.wherelee.cabinet.locker.api.CabinetLockPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * {@link CabinetLockPort} 的进程内实现：redisson 策略下委托 {@link CabinetLockGuard}，
 * 其他策略下直接执行（不加锁，靠分配器自己的条件更新）。
 */
@Component
public class CabinetLockAdapter implements CabinetLockPort {

    /** 只有 redisson 策略会注册这个 bean；其他策略下为空。 */
    private final ObjectProvider<CabinetLockGuard> guardProvider;

    public CabinetLockAdapter(ObjectProvider<CabinetLockGuard> guardProvider) {
        this.guardProvider = guardProvider;
    }

    @Override
    public boolean active() {
        return guardProvider.getIfAvailable() != null;
    }

    @Override
    public <T> T aroundCabinet(String cabinetNo, Supplier<T> action) {
        CabinetLockGuard guard = guardProvider.getIfAvailable();
        return guard == null ? action.get() : guard.aroundCabinet(cabinetNo, action);
    }
}
