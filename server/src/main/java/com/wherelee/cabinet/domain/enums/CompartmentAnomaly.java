package com.wherelee.cabinet.domain.enums;

import java.util.Locale;

/**
 * 格口异常原因（门与物）。
 *
 * <p><b>为什么不能只有"异常/正常"两态</b>：同一句"这格异常"背后是四种完全不同的现场动作。
 * 门要关一下、遗留物要开箱取件、测不到要人来确认、传感器矛盾要判定归哪类。
 * 合成一个标记，业务运维到场就只能全部当"遗留物"处理，白跑率立刻上来。
 *
 * <p><b>为什么这里不含硬件故障与人工停用</b>：那两件事已经有 {@link SlotStatus#FAULT}
 * 与 {@link SlotStatus#MAINTENANCE}（第 8 刀，且自动恢复与不自动恢复的区别已经定过）。
 * 再放进来就会出现"格口同时是 MAINTENANCE 又是 CONTENT_LEFT"这种两处真相、
 * 谁也说不清谁优先的状态。所以这个枚举只覆盖正交的那一维：<b>门与物</b>。
 *
 * <p>两个判据都写成枚举方法而不是在业务代码里 switch，是为了让"新增一类异常"必须
 * 同时回答这两个问题——漏答就是在编译期之后靠运气补。
 */
public enum CompartmentAnomaly {

    /** 门未关。计费继续（§2 的惩罚靠这条成立）；门关且柜内无物即自动解除。 */
    DOOR_OPEN,
    /** 柜内有遗留物（用户声明放弃，或客服确认）。订单已结束，但只能由人清柜放行。 */
    CONTENT_LEFT,
    /** 门关了但柜内情况测不到（设备无物检能力或报 UNKNOWN）。真实柜机大多只有门磁，这一类将来占大头。 */
    CONTENT_UNVERIFIED,
    /** 传感器自相矛盾：设备称已关门但门磁未确认，或门磁与物检互斥。需人工判定归入哪一类。 */
    SENSOR_CONFLICT;

    /**
     * 这个异常要不要继续计费。
     *
     * <p>{@link #CONTENT_LEFT} 不续计是因为它<b>只出现在订单已终态之后</b>——
     * 单已经结了，钱的事到此为止，剩下的是货的问题（清柜）。
     * 把它写成"继续计费"会让一个已结束的单凭空长出费用，那才是真事故。
     */
    public boolean keepsBilling() {
        return this == DOOR_OPEN || this == SENSOR_CONFLICT;
    }

    /**
     * 能不能由系统自己解除。
     *
     * <p>只有"门未关"可以：门关 + 柜内无物就自动正常。其余三类<b>必须有人参与</b>
     * （不变量 I9：不存在任何超时自动放开路径）。少卖是钱，卖错是事故，
     * 这两类代价不对等，所以宁可让柜机可用数暂时下降。
     */
    public boolean autoRecoverable() {
        return this == DOOR_OPEN;
    }

    public static CompartmentAnomaly of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
