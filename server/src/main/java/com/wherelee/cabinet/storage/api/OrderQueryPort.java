package com.wherelee.cabinet.storage.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 寄存订单的只读端口：其他服务要看"这张单是什么"，只能走这里。
 *
 * <p>为什么先拆查询而不是写：跨上下文里最频繁、最难缠的是"顺手 join 一下"——
 * 柜机控制台要看当前订单、工单要看订单号、运营台要看客户与格口。让它们各自查表，
 * 边界就永远立不起来；只读端口是最先能落地的一刀。
 *
 * <p>返回 {@link OrderSnapshot} 而不是实体：实体带着 active_flag/version/快照 JSON，
 * 一旦外部拿它当业务状态用，改表就会击穿调用方。
 */
public interface OrderQueryPort {

    Optional<OrderSnapshot> byId(Long orderId);

    List<OrderSnapshot> byIds(Collection<Long> orderIds);

    Optional<OrderSnapshot> byOrderNo(String orderNo);
}
