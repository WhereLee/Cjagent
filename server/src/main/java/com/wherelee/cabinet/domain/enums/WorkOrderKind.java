package com.wherelee.cabinet.domain.enums;

import java.util.Locale;

/**
 * 工单大类：异常与用户请求共用一张表，但语义不同。
 *
 * <p>INCIDENT 是设备/格口已经发生的事实；REQUEST 是用户报来的问题。
 * 分开是为了让“系统发现的”和“用户说的”在统计、责任链和关闭依据上不混在一起。
 */
public enum WorkOrderKind {

    /** 设备/格口异常，由巡检或事实检测开出。 */
    INCIDENT,
    /** 用户请求，由 AI 客服或人工客服转报开出。 */
    REQUEST;

    public static WorkOrderKind of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
