package com.wherelee.cabinet.storage.api;

/**
 * 异步落库端口：预扣路径下“消息消费落库”与“空闲集合同步”的入口。
 *
 * <p>为什么单独一个端口：消息监听器与后台校准入口都只做一件事——把消息/柜机交给订单服务，
 * 不该因此拿到整个 {@code AsyncStorageOrderAppService}（它还带着预扣、退单等写能力）。
 */
public interface OrderPersistPort {

    /** 消费落库（幂等由消费守卫与订单号唯一索引保证）。 */
    void persist(OrderPersistMessage message);

    /** 用数据库真相重建某柜机的空闲集合（测试与运维校准入口）。 */
    void syncFreeSets(Long cabinetId);
}
