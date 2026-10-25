package com.wherelee.cabinet.locker.api;

import java.util.function.Supplier;

public interface CabinetLockPort {

    boolean active();

    <T> T aroundCabinet(String cabinetNo, Supplier<T> action);
}
