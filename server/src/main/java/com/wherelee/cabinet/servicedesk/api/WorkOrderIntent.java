package com.wherelee.cabinet.servicedesk.api;

import java.util.Locale;

/**
 * 用户现象（intent）：用户说发生了什么，不是系统判的处置分类。
 *
 * <p>intent 与 category 分开：同样一句“柜门打不开”，可能是设备故障，也可能是订单已结束。
 * 先记用户说的，再由分诊规则结合事实判 category。
 */
public enum WorkOrderIntent {

    /** 柜门打不开/开门命令没反应。 */
    DOOR_NOT_OPEN,
    /** 柜门关不上。 */
    DOOR_NOT_CLOSE,
    /** 东西不见了/怀疑被别人取走。 */
    ITEM_MISSING,
    /** 柜里有别人的东西/遗留物。 */
    ITEM_LEFT,
    /** 整柜没反应/离线/屏幕不亮。 */
    DEVICE_OFFLINE,
    /** 费用不对/多扣费/计费争议。 */
    BILLING_DISPUTE,
    /** 取件码不能用/收不到取件码。 */
    PICKUP_CODE,
    /** 其他现象。 */
    OTHER;

    public static WorkOrderIntent of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
