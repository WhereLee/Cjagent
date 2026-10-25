package com.wherelee.cabinet.locker.api;

import com.wherelee.cabinet.common.api.PageQuery;
import com.wherelee.cabinet.common.api.PageResult;

import java.time.LocalDateTime;

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

    /**
     * 死信列表。
     *
     * <p>为什么不是“给最近 N 条”：死信堆积正是最需要处理的时候，写死条数会让
     * “能不能翻到第 51 条”变成运气。分页沿用项目已有标准（{@code PageQuery} / {@code PageResult}）。
     *
     * @param status OPEN / REPLAYED；为空则两种都返回（运维要能回看已经重放过什么）
     */
    PageResult<DeadLetterView> list(String status, PageQuery query);

    /**
     * 列表行。
     *
     * <p><b>为什么带 {@code payload}</b>：这个界面的全部意义是“先看这条消息是什么、为什么失败，
     * 再决定要不要重放”。看不到正文就只能盲点重放，那比不给按钮更危险。
     *
     * <p><b>为什么带 {@code dlqTopic}</b>：死信按消费组分开（工单 / 通知），运维要先知道是哪条链路卡住了。
     *
     * <p><b>为什么带 {@code replayedAt}</b>：已经重放过的要能看见什么时候重放的，否则无法审计。
     *
     * <p><b>为什么叫 {@code DeadLetterView} 而不是 {@code View}</b>：SpringDoc 按简单类名生成 schema，
     * 本项目已有 5 个内嵌 record 都叫 {@code View}（定价/工单/通知/AI 作业/死信），
     * 同名会互相覆盖——契约里只会剩一份，其余字段全部丢失（工单行类型就是这么丢的，
     * 所以前端才要在 {@code contracts.ts} 手写）。这里取唯一名字，让死信的字段真正进契约。
     */
    record DeadLetterView(Long id, String topic, String tag, String dlqTopic, String msgKey, String payload,
                          String error, int reconsumeTimes, String status,
                          LocalDateTime receivedAt, LocalDateTime replayedAt) {
    }
}
