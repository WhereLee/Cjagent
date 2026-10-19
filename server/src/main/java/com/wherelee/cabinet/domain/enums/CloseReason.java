package com.wherelee.cabinet.domain.enums;

import java.util.Locale;

/** 关闭依据：谁/什么事实让这张单可以结束。 */
public enum CloseReason {

    /** 人工处理结论。 */
    MANUAL,
    /** 异常事实自行消失（门关上了、遗留被取走）。 */
    FACT_RECOVERED,
    /** 用户确认问题已解决。 */
    USER_CONFIRMED,
    /** 重复单合并到主单。 */
    MERGED,
    /** 作废。 */
    CANCELLED;

    public static CloseReason of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
