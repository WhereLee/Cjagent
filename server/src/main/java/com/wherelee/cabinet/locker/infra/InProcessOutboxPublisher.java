package com.wherelee.cabinet.locker.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherelee.cabinet.domain.entity.BizOutbox;
import com.wherelee.cabinet.locker.api.OutboxPort.OwnerNoticePayload;
import com.wherelee.cabinet.locker.api.OutboxPort.WorkOrderAnomalyPayload;
import com.wherelee.cabinet.locker.api.OutboxPublisher;
import com.wherelee.cabinet.notify.api.NoticePort;
import com.wherelee.cabinet.servicedesk.api.WorkOrderPort;
import org.springframework.stereotype.Component;

/**
 * 进程内投递：在同一个 JVM 里直接调工单/通知端口。
 *
 * <p>这段逻辑原来长在 {@code OutboxRelay.dispatch} 里，行为一个字没改，只是搬了位置——
 * 搬家的目的是让"投递方式"变成可替换的。
 *
 * <p><b>它不该是生产的最终形态</b>：同进程调用拿不到 outbox 的解耦收益（中继仍然知道
 * 消费者是谁）。接上 broker 之后，这个实现只留给测试当桩，用来断言"投递了什么"
 * 而不真的发消息。
 */
@Component
public class InProcessOutboxPublisher implements OutboxPublisher {

    private final WorkOrderPort workOrders;
    private final NoticePort notices;
    private final ObjectMapper json;

    public InProcessOutboxPublisher(WorkOrderPort workOrders, NoticePort notices, ObjectMapper json) {
        this.workOrders = workOrders;
        this.notices = notices;
        this.json = json;
    }

    /** 按事件类型投递。载荷用同一个 record 反序列化——生产方与消费方共用形状，改字段两边一起变。 */
    @Override
    public void publish(BizOutbox row) {
        switch (row.getEventType()) {
            case WORK_ORDER_ANOMALY -> {
                WorkOrderAnomalyPayload payload = read(row, WorkOrderAnomalyPayload.class);
                workOrders.openForAnomaly(payload.slotId(), payload.anomaly(), payload.reason());
            }
            case OWNER_NOTICE -> {
                OwnerNoticePayload payload = read(row, OwnerNoticePayload.class);
                notices.emit(row.getTenantId(), payload.channel(), payload.kind(), payload.dedupKey(),
                        payload.content(), payload.customerId(), payload.workOrderId(), payload.orderNo());
            }
        }
    }

    private <T> T read(BizOutbox row, Class<T> type) {
        try {
            return json.readValue(row.getPayload(), type);
        } catch (Exception e) {
            // 载荷读不出来说明写入时就是坏数据：让中继的 failSoft 去退避与判死，别在这里吞掉
            throw new IllegalStateException("outbox 载荷无法解析 id=" + row.getId(), e);
        }
    }
}
