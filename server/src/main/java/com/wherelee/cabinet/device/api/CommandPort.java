package com.wherelee.cabinet.device.api;

import java.util.List;

/**
 * 设备指令端口：下单开门、后台强开、遗留物取回都从这里下发。
 *
 * <p>指令表只由设备服务写。调用方拿到的只是结果快照，不碰 {@code biz_device_command}。
 * 之前订单侧自己数"这是第几次尝试"、自己查指令表，正是耦合的源头。
 */
public interface CommandPort {

    /** 下发一条指令；requestId 是幂等键，同一个值不会重复开柜。 */
    CommandOutcome dispatch(Long cabinetId, Long slotId, CommandAction action, Long orderId, String requestId);

    /**
     * 某类动作已经尝试过几次（按 requestId 前缀数）。
     *
     * <p>调用方拼 {@code orderNo:ACTION:} 前缀，由设备服务负责计数——"第几次"是设备侧的账，
     * 不该由订单侧自己去查指令表。
     */
    long countAttempts(String requestIdPrefix);

    /** 一张单的指令历史（运营台详情用），新→旧。 */
    List<CommandSnapshot> byOrder(Long orderId, int limit);

    /** 这张单是否已有一次成功的开门（首次投件或临时开柜）。 */
    boolean anyOpenSucceeded(Long orderId);
}
