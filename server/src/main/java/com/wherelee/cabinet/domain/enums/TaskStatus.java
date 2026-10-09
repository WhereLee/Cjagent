package com.wherelee.cabinet.domain.enums;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * 延迟任务状态。
 *
 * <p>{@link #RUNNING} 带租约（{@code lease_owner + lease_expire_at}）：实例被 kill 时任务
 * 不会永久卡死，租约过期可被别人接管。<b>没有租约的"运行中"状态是分布式调度的头号事故源</b>
 * ——进程死了，任务看起来还在跑，于是永远不会有人再执行它。
 *
 * <p>{@link #DEAD} 才是终态：重试次数耗尽，必须人工介入（后台工单，第 14 刀）。
 * {@link #FAILED} 留给"这次失败但还要再试"，与 attempt/退避一起看。
 */
public enum TaskStatus {

    PENDING,
    RUNNING,
    DONE,
    FAILED,
    DEAD;

    private static final Set<TaskStatus> TERMINAL = EnumSet.of(DONE, DEAD);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    public static TaskStatus of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
