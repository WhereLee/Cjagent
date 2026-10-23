package com.wherelee.cabinet.locker.api;

import com.wherelee.cabinet.domain.entity.BizOutbox;

/**
 * 把一条待投递的事实送出去。
 *
 * <p>为什么单独开一个口子、而不是让中继直接调工单/通知端口：outbox 这个模式的前提是
 * <b>生产者不需要知道消费者是谁</b>。中继里写死 switch，新增一个订阅方就得改中继——
 * 那 outbox 只拿到了"事务隔离"那一半，丢了"解耦"那一半。
 *
 * <p>实现必须遵守一条契约：<b>正常返回代表对方已收下</b>。中继靠这个判断能不能
 * 把该行标成 {@code DONE}；抛异常就按退避重试，5 次后判死。
 * 半途失败却静默返回，等于把一条事实悄悄丢掉。
 */
public interface OutboxPublisher {

    /**
     * @throws RuntimeException 投递失败。中继会退避重试，超过预算后标 {@code DEAD} 交人工
     */
    void publish(BizOutbox row);
}
