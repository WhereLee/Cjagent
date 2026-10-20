package com.wherelee.cabinet.notify.api;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 通知端口：所有"该让某人看到一次"的事都从这里出去。
 *
 * <p>系统里没有短信、外呼、推送，所以这里不假装发出去过：
 * <ul>
 *   <li>{@link #CHANNEL_PHONE}：挂在工单上的待办"必须电话联系客户"，打完回填；</li>
 *   <li>{@link #CHANNEL_MESSAGE}：站内消息，客户打开小程序才看得到；</li>
 *   <li>{@link #CHANNEL_QUEUE}：队列侧通知，表示"这件事已收口"，不是待办。</li>
 * </ul>
 *
 * <p>去重靠唯一索引（tenant + dedup_key），不是"先查再插"——提醒阶梯每轮都会重进，
 * 先查再插在并发下会堆出一串一模一样的待办。
 */
public interface NoticePort {

    String CHANNEL_MESSAGE = "SYSTEM_MESSAGE";
    String CHANNEL_PHONE = "PHONE_CALL";
    String CHANNEL_QUEUE = "QUEUE";

    /** 视图：接口层不回传实体。 */
    record View(Long id, String channel, String kind, String content, String orderNo,
                Long workOrderId, LocalDateTime createTime, LocalDateTime doneAt,
                String doneNote) {
    }

    /** @return true 表示这是第一次发出（false = 同一 dedupKey 已发过） */
    boolean emit(Long tenantId, String channel, String kind, String dedupKey, String content,
                 Long customerId, Long workOrderId, String orderNo);

    /** 改写一条已发消息的内容（比如重置取件码后，凭证消息必须反映当前码）。 */
    void rewrite(String dedupKey, String content);

    List<View> forCustomer(Long customerId);

    List<View> pendingCalls();

    void markRead(Long id, Long customerId);

    void markCallDone(Long id, String note);
}
