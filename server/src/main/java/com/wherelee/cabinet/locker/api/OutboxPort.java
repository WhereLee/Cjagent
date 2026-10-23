package com.wherelee.cabinet.locker.api;

import com.wherelee.cabinet.notify.api.NoticeChannel;

/**
 * 待投递事实的入队口。
 *
 * <p>调用方在<b>自己的业务事务里</b>调用它：同一事务里写一行本地表不会失败，
 * 于是"事实已发生"与"派生动作待做"一起落定，下游投递失败不会再回滚事实。
 * 这是 {@code SlotAnomalyPublisher} 那两处扇出的替代品——原来它们同步调工单与通知，
 * 在下游抛异常时会把共享的物理事务标成 rollback-only，用户取件/上报会跟着失败。
 *
 * <p>为什么参数是具体事实而不是一段 JSON：载荷的字段名只有生产方与消费方知道，
 * 让调用方拼字符串等于把契约交给字符串。
 */
public interface OutboxPort {

    /**
     * 格口异常落定后要开的工单。
     *
     * @param dedupKey 去重键。同一事实只入队一次；同一格口同一异常在不同时刻各算一次事实
     *                 （开单本身还有唯一索引兜底，重复投递不会开出第二张）
     */
    void appendWorkOrderAnomaly(Long slotId, CompartmentAnomaly anomaly, String reason, String dedupKey);

    /** 遗留物归因到终态单后要发的站内消息。{@code dedupKey} 沿用 {@code WO{工单ID}:OWNER_MSG}。 */
    void appendOwnerNotice(NoticeChannel channel, String kind, String dedupKey, String content,
                           Long customerId, Long workOrderId, String orderNo);

    /** 载荷形状：生产方与消费方共用同一个 record，改字段两边一起变。 */
    record WorkOrderAnomalyPayload(Long slotId, CompartmentAnomaly anomaly, String reason) {
    }

    /** 载荷形状：通知的 7 个参数原样带上，投递时不再回查（事实快照）。 */
    record OwnerNoticePayload(NoticeChannel channel, String kind, String dedupKey, String content,
                              Long customerId, Long workOrderId, String orderNo) {
    }
}
