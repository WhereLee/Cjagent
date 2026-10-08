package com.wherelee.cabinet.domain.enums;

import java.util.Locale;

/**
 * 设备上报的事件类型（对应 {@code biz_device_report.event_type}）。
 *
 * <p>{@link #DOOR_CLOSED} 的语义要单独强调：<b>"设备说门关了"不等于"门真的关了"</b>。
 * 业务侧必须结合传感器确认位判断（见 {@code CommandReceipt#sensorConfirmed}），
 * 谎报正是本项目的核心故障场景之一（S-06 设备谎报关门）。
 */
public enum ReportEvent {

    /** 门已打开 */
    DOOR_OPENED,
    /** 门已关闭（需传感器确认位佐证） */
    DOOR_CLOSED,
    /** 心跳（在线判定，拓展项） */
    HEARTBEAT,
    /** 设备自报故障 */
    FAULT,
    /** 状态快照（对账用） */
    STATE_SNAPSHOT;

    public static ReportEvent of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
