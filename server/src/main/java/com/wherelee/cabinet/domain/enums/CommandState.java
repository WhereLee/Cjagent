package com.wherelee.cabinet.domain.enums;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 设备指令状态机。
 *
 * <p>与订单状态机的关键区别：<b>{@link #FAILED} 与 {@link #TIMEOUT} 都不是终态</b>。
 * 设备类故障大多是瞬时的（门卡一下、网络抖一下），把它们当终态就等于
 * "一次抖动判死一张单"，用户被锁在柜机前。是否放弃由 {@code retry_count} 阈值决定，
 * 而阈值判断属于业务（第 13 刀调度），不属于状态机。
 *
 * <p>特别注意 {@code TIMEOUT → SUCCEEDED} 这条边<b>必须存在</b>：
 * 回执晚于超时判定到达是真实存在的场景，此时"门其实开了"必须能修正状态。
 * 反过来（先成功后超时）不允许——终态不回退。
 */
public enum CommandState {

    /** 已入库，还没下发 */
    PENDING,
    /** 已交给通道，等待回执 */
    SENT,
    /** 设备确认收到（尚未执行完） */
    ACKED,
    /** 执行成功（终态） */
    SUCCEEDED,
    /** 设备明确报失败（可重试） */
    FAILED,
    /** 超时未回（可重试，也可能随后被晚到的回执修正） */
    TIMEOUT;

    private static final Set<CommandState> TERMINAL = EnumSet.of(SUCCEEDED);

    private static final Map<CommandState, Set<CommandState>> TRANSITIONS = new EnumMap<>(CommandState.class);

    static {
        TRANSITIONS.put(PENDING, EnumSet.of(SENT, FAILED, TIMEOUT));
        TRANSITIONS.put(SENT, EnumSet.of(ACKED, SUCCEEDED, FAILED, TIMEOUT));
        TRANSITIONS.put(ACKED, EnumSet.of(SUCCEEDED, FAILED, TIMEOUT));
        // 重试：失败/超时都可以重新下发；超时后晚到的成功也要能落地
        TRANSITIONS.put(FAILED, EnumSet.of(SENT, TIMEOUT));
        TRANSITIONS.put(TIMEOUT, EnumSet.of(SENT, SUCCEEDED, FAILED));
        TRANSITIONS.put(SUCCEEDED, EnumSet.noneOf(CommandState.class));

        for (CommandState state : values()) {
            TRANSITIONS.computeIfAbsent(state, k -> EnumSet.noneOf(CommandState.class));
        }
        TERMINAL.forEach(terminal -> {
            if (!TRANSITIONS.get(terminal).isEmpty()) {
                throw new IllegalStateException("指令终态不允许有出边: " + terminal);
            }
        });
    }

    public boolean canTransitTo(CommandState target) {
        return TRANSITIONS.getOrDefault(this, EnumSet.noneOf(CommandState.class)).contains(target);
    }

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** 是否还可以重新下发（重试上限由业务判断）。 */
    public boolean isRetriable() {
        return this == FAILED || this == TIMEOUT;
    }

    public static CommandState of(String value) {
        return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    }
}
