package com.wherelee.cabinet.application.task;

import com.wherelee.cabinet.domain.enums.TaskType;

import java.time.LocalDateTime;

/**
 * "提醒"端口：告诉 worker "这个时间到了，去看一眼"。
 *
 * <p><b>它不携带执行权</b>：能不能执行永远由 {@code biz_delay_task} 上的条件更新决定。
 * 所以提醒丢了、重了、乱了都不影响正确性，只影响延迟。这条边界让三种实现可以共存互换。
 */
public interface DelayReminder {

    void remind(TaskType type, String bizKey, LocalDateTime fireAt);

    /** 实现名，进日志与指标，避免"到底在用哪种触发"事后说不清。 */
    String mode();
}
