package com.wherelee.cabinet.application.storage;

import com.wherelee.cabinet.application.storage.message.OrderPersistMessage;

/**
 * 落库消息发送端口。
 *
 * <p>application 层只声明"要发一条消息"，不认识 RocketMQ——实现放在 infrastructure
 * （ArchUnit 依赖方向规则会拦住反向引用）。这样换 broker 或加本地队列兜底都不用改用例代码。
 */
public interface OrderPersistPublisher {

    /**
     * 发送"待落库"消息。
     *
     * <p><b>必须同步发送并让异常上抛</b>：异步发送（{@code asyncSend}）把失败藏在回调里，
     * 调用方就无法在失败时归还预扣的格口，用户会拿着一个永远不会落库的占位码。
     */
    void publish(OrderPersistMessage message);
}
