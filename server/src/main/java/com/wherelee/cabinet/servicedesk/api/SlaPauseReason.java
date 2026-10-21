package com.wherelee.cabinet.servicedesk.api;

/**
 * SLA 暂停原因。
 *
 * <p>本轮只定义「等待用户」：业务员把单挂起等客户回应，SLA 暂停；用户回应后由业务员
 * <b>手动恢复</b>。没有自动恢复、也没有等待超时自动关单——那两条都会改变工单的关闭语义。
 *
 * <p>落库存枚举名；读取端用 {@link #ofNullable} 容错（历史行/手工改库的未知值返回 null，
 * 展示层按普通暂停处理，不让整页报错）。
 */
public enum SlaPauseReason {

    /** 等待用户回应（业务员标记，业务员恢复）。 */
    WAITING_CUSTOMER;

    public static SlaPauseReason ofNullable(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (SlaPauseReason value : values()) {
            if (value.name().equals(raw)) {
                return value;
            }
        }
        return null;
    }
}
