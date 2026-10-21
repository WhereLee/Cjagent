package com.wherelee.cabinet.notify.api;

/**
 * 通知渠道（落库值即枚举名，历史数据不变）。
 *
 * <p>值集是封闭的：系统里只有这三种"该被看到一次"的形式（站内消息 / 电话待办 / 队列收口通知）。
 * 没有短信、外呼、推送，所以不预留 {@code SMS} 之类没有实现的口子。
 *
 * <p>读取端用 {@link #ofNullable} 容错：历史行里若出现未知值（手工改库/回滚残留），
 * 展示层按"未知渠道"原样返回字符串，不抛异常把整页打挂。
 */
public enum NoticeChannel {

    /** 站内消息：客户打开小程序才看得到。 */
    SYSTEM_MESSAGE,
    /** 挂在工单上的待办："必须电话联系客户"，打完回填。 */
    PHONE_CALL,
    /** 队列侧通知：表示"这件事已收口"，不是待办。 */
    QUEUE;

    public static NoticeChannel ofNullable(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (NoticeChannel value : values()) {
            if (value.name().equals(raw)) {
                return value;
            }
        }
        return null;
    }
}
