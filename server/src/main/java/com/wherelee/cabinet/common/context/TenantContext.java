package com.wherelee.cabinet.common.context;

import com.alibaba.ttl.TransmittableThreadLocal;

import java.util.concurrent.Callable;

public final class TenantContext {

    private static final ThreadLocal<Long> CURRENT = new TransmittableThreadLocal<>();

    private TenantContext() {
    }

    public static void set(Long tenantId) {
        CURRENT.set(tenantId);
    }

    public static Long current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static void runAs(Long tenantId, Runnable action) {
        callAs(tenantId, () -> {
            action.run();
            return null;
        });
    }

    public static <T> T callAs(Long tenantId, Callable<T> action) {
        Long previous = CURRENT.get();
        try {
            CURRENT.set(tenantId);
            return action.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("租户内执行失败: tenantId=" + tenantId, e);
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
