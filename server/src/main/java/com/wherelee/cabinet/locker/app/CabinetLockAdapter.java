package com.wherelee.cabinet.locker.app;

import com.wherelee.cabinet.locker.api.CabinetLockPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

@Component
public class CabinetLockAdapter implements CabinetLockPort {

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
