package com.wherelee.cabinet.domain.enums;

import java.util.Locale;

/**
 * 故障类型与处置动作（{@code biz_fault_event} 的两列）。
 *
 * <p>每个类型都对应一条明确的处置路径，这是"防呆"能否落地的关键：
 * <ul>
 *   <li>{@link #OPEN_FAILED}：开柜失败 → 格口保持占用、订单可重试，绝不能变成"件在里面、单子没了"；</li>
 *   <li>{@link #DOOR_NOT_CLOSED}：谎报关门 → 判异常等人工，宁可不结束也不把件锁死；</li>
 *   <li>{@link #NO_REPORT}：超时无回执 → 保留占位并允许重试；</li>
 *   <li>{@link #TARGET_MISMATCH}：回执里的格口与指令不符（部分成功/错报目标）→ 只认成功的那个；</li>
 *   <li>{@link #OFFLINE}：柜机离线 → 按"不可存、可取"降级（S-09）；</li>
 *   <li>{@link #CONSECUTIVE_FAIL}：连续失败达阈值 → 停用柜机/格口，别再放新单进去。</li>
 * </ul>
 */
public enum FaultType {

    OPEN_FAILED,
    DOOR_NOT_CLOSED,
    NO_REPORT,
    TARGET_MISMATCH,
    OFFLINE,
    CONSECUTIVE_FAIL;

    public static FaultType of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    /** 故障的处置动作，与 {@code biz_fault_event.action} 列对应。 */
    public enum Action {
        /** 标记不可用（格口或柜机停用） */
        MARK_UNAVAILABLE,
        /** 标记恢复 */
        MARK_ONLINE,
        /** 仅告警，不改状态 */
        ALARM,
        /** 人工或补偿任务恢复 */
        RECOVER
    }
}
