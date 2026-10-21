package com.wherelee.cabinet.device.api;

/** 证据采集点（用户原话：开门一张、关门一张、5 分钟检测到有物一张、他人代关后一张）。 */
public enum EvidenceMoment {
    /** 格口刚打开（此时柜内应为空基准）。 */
    DOOR_OPENED,
    /** 格口关上（带物检结论，是遗留判定的主凭据）。 */
    DOOR_CLOSED,
    /** 看管期间的定时检测（门未关阶梯上每发一次提醒做一次）。 */
    WATCH_DETECT,
    /** 另一位用户代关之后的检测（用于与代关前的记录对比）。 */
    HELPER_CLOSED,
    /** 物主自助取回后的复检（第 21 刀）：确认柜内已空才放格子回可售。 */
    RETRIEVED_CHECK
}
