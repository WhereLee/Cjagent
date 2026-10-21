package com.wherelee.cabinet.locker.api;

/**
 * 空闲集合端口：格口空出来之后要回到“可分配集合”，集合由 locker 维护。
 *
 * <p>为什么不让订单侧直接改集合：预扣集合是 locker 的运行时结构（四种分配策略共用一份），
 * 订单只需要说“这一格空了”，怎么加回去、加不加得进去由格口服务决定。
 */
public interface SlotPoolPort {

    void addFree(Long cabinetId, SizeType sizeType, Long slotId);
}
