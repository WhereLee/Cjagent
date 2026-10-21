package com.wherelee.cabinet.servicedesk.api;

import java.util.Locale;

/**
 * 处置分类（category）：系统根据 intent + 事实判出来的处理类别。
 *
 * <p>它与 {@link WorkOrderType} 的区别：type 是既有异常类型，继续服务唯一索引和旧队列；
 * category 是智能工单的处置分类，可以覆盖用户请求。两者在 INCIDENT 上重叠，在 REQUEST 上互补。
 */
public enum WorkOrderCategory {

    /** 开关门设备故障。 */
    DOOR_FAULT,
    /** 门未关事实。 */
    DOOR_NOT_CLOSED,
    /** 物品争议/丢失。 */
    ITEM_DISPUTE,
    /** 遗留物处理。 */
    ITEM_LEFT,
    /** 整柜/设备离线。 */
    DEVICE_OFFLINE,
    /** 费用争议。 */
    BILLING_DISPUTE,
    /** 取件码问题。 */
    PICKUP_CODE,
    /** 规则未命中，等待人工分拣。 */
    UNSORTED;

    public static WorkOrderCategory of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
