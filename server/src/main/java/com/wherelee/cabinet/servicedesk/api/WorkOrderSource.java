package com.wherelee.cabinet.servicedesk.api;

import java.util.Locale;

/** 工单来源：谁先把这件事变成了一张单。 */
public enum WorkOrderSource {

    /** 设备/巡检检测到的事实。 */
    DEVICE,
    /** 用户在小程序里主动上报。 */
    USER,
    /** AI 客服转报。 */
    AI,
    /** 定时任务或系统规则自动开出。 */
    SYSTEM;

    public static WorkOrderSource of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
