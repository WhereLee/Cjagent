package com.wherelee.cabinet.application.storage.message;

/**
 * "订单待落库"消息。
 *
 * <p>字段是<b>消费者落库所需的最小集合</b>，不带实体对象：消息一旦承载实体的序列化形态，
 * 实体的每次改动都变成"消息契约变更"，而契约变更需要生产与消费两侧同时上线。
 *
 * <p>{@code traceId} 必须由生产方放进去：异步链路的日志如果不接回 HTTP 请求，
 * 排障时就会出现"这条订单为什么没落库"却无从定位是哪一次点击。
 *
 * <p>{@code msgKey()} 用订单号而不是 broker msgId：重投时 msgId 可能变，
 * 而业务幂等键必须稳定。
 */
public record OrderPersistMessage(String orderNo,
                                  Long orderId,
                                  Long tenantId,
                                  Long customerId,
                                  Long cabinetId,
                                  Long slotId,
                                  String sizeType,
                                  String voucherCode,
                                  Integer estimateMinutes,
                                  String pricingSnapshot,
                                  String requestId,
                                  String traceId) {

    public static final String TOPIC_DEFAULT = "storage-order-persist";

    public String msgKey() {
        return orderNo;
    }
}
