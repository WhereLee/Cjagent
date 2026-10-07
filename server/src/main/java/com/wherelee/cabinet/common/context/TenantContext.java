package com.wherelee.cabinet.common.context;

import com.alibaba.ttl.TransmittableThreadLocal;

import java.util.concurrent.Callable;

/**
 * 租户上下文：当前请求属于哪个租户。
 *
 * <p>用 {@link TransmittableThreadLocal} 而不是普通 ThreadLocal：业务里一定会用线程池
 * （异步通知、MQ 消费、定时任务），普通 ThreadLocal 在池化线程上拿到的是<b>上一个任务残留的值</b>，
 * 结果是"给 A 租户的记录写进了 B 租户"——这种越租污染不会有报错，只会对不上账。
 *
 * <p>注意 TTL 的两种生效方式：
 * <ul>
 *   <li>包装线程池（{@code TtlExecutors.getTtlExecutorService(...)}）或 javaagent 方式，
 *       对<b>提交任务时</b>的快照自动传递；</li>
 *   <li>{@link #runAs} / {@link #callAs}：显式在目标线程内临时设置，用于不便包装的执行路径。</li>
 * </ul>
 *
 * <p>{@link #clear()} 必须成对调用（拦截器/过滤器的 finally 里），否则线程复用会串租户。
 */
public final class TenantContext {

    private static final ThreadLocal<Long> CURRENT = new TransmittableThreadLocal<>();

    private TenantContext() {
    }

    public static void set(Long tenantId) {
        CURRENT.set(tenantId);
    }

    /** 当前租户；未设置时返回 null（调用方决定是拒绝还是放行，本类不做策略判断）。 */
    public static Long current() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** 在指定租户下执行一段逻辑，执行完恢复原值（不是直接清空，避免嵌套调用丢上下文）。 */
    public static void runAs(Long tenantId, Runnable action) {
        callAs(tenantId, () -> {
            action.run();
            return null;
        });
    }

    /** {@link #runAs} 的有返回值版本。 */
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
