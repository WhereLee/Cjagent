package com.wherelee.cabinet.domain.enums;

/**
 * 柜机状态（业务开关），与在线状态是两个维度，故意不合成一个枚举：
 * <b>离线柜机仍然必须能取件</b>（降级方向见 S-09），若把 OFFLINE 塞进这里，
 * 很容易写出"柜机不可用就一律拒绝请求"的代码，把人的行李锁在柜子里。
 */
public enum CabinetStatus {

    /** 正常营业 */
    ENABLED,
    /** 人工停用（不接新单，存量单仍可取件） */
    DISABLED,
    /** 故障停用（由故障计数自动触发，恢复后可回到 ENABLED） */
    FAULT
}
