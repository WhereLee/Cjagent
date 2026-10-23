package com.wherelee.cabinet.locker.api;

import java.time.LocalDateTime;
import java.util.List;

/**
 * MQ 死信的归档、查询与重放。
 *
 * <p>为什么归档和重放要放在同一个口子里：它们操作的是同一行、同一份证据。
 * 分成两个服务，迟早会出现"重放时不知道归档时长什么样"这种漂移。
 */
public interface DeadLetterPort {

    /**
     * 归档一条进死信的消息。
     *
     * <p>{@code topic} / {@code tag} 是<b>原来的业务 topic 与 tag</b>（重放目标），
     * {@code dlqTopic} 是死信实际所在的 {@code %DLQ%xxx}——三者都要存，
     * 否则重放时不知道该投回哪里。
     *
     * @param tenantId 从消息 header 取；坏消息取不到时传 null，那条将不可重放
     */
    void archive(String topic, String tag, String dlqTopic, Long tenantId,
                 String msgKey, String payload, String error, int reconsumeTimes);

    /**
     * 重放一条。投回原 topic + tag，并把状态改成 REPLAYED。
     *
     * @return false = 不存在、已重放过、或没有租户信息（坏消息，只能人工看）
     */
    boolean replay(Long id);

    /** 待处理（OPEN）的死信，最近的在前。 */
    List<View> listOpen(int limit);

    record View(Long id, String topic, String tag, String msgKey, String error,
                int reconsumeTimes, String status, LocalDateTime receivedAt) {
    }
}
