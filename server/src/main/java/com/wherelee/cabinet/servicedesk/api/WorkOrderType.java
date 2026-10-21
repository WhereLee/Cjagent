package com.wherelee.cabinet.servicedesk.api;

import java.util.Locale;

/**
 * 工单类型（19B）。
 *
 * <p>只收录“已经发生的异常类别”，不要在这里加“处理动作”：动作属于 {@link WorkOrderStatus}
 * 与事件流水。类型和级别分开是因为同一类型既可能普通也可能紧急（用户口径：订单中门没关是普通
 * 异常，订单中有物门没关是紧急异常），队列要能按级别排序，而不是把四五种组合拆成五六个类型。
 */
public enum WorkOrderType {

    /** 门未关：订单进行中或结束后的现场事实。柜内有物时升级为紧急。 */
    DOOR_NOT_CLOSED,
    /** 遗留物：订单结束后柜内仍检测到物品，待 AI 简单标签与人工判断。 */
    ITEM_LEFT,
    /** 开关门故障：开门失败且重试无效、门关不上、取件时门打不开。 */
    DEVICE_FAULT,
    /** 超封顶仍未结束（计费封顶 48 小时）：需要联系用户，72 小时后线下可介入。 */
    LONG_RUNNING;

    public static WorkOrderType of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
