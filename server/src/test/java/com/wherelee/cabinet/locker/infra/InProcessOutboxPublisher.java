package com.wherelee.cabinet.locker.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherelee.cabinet.domain.entity.BizOutbox;
import com.wherelee.cabinet.locker.api.OutboxPort.OwnerNoticePayload;
import com.wherelee.cabinet.locker.api.OutboxPort.WorkOrderAnomalyPayload;
import com.wherelee.cabinet.locker.api.OutboxPublisher;
import com.wherelee.cabinet.notify.api.NoticePort;
import com.wherelee.cabinet.servicedesk.api.WorkOrderPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 进程内投递：在同一个 JVM 里直接调工单/通知端口。
 *
 * <p>这段逻辑原来长在 {@code OutboxRelay.dispatch} 里，行为一个字没改，只是搬了位置——
 * 搬家的目的是让"投递方式"变成可替换的。
 *
 * <p><b>它只存在于测试源码里</b>（{@code src/test}），生产 jar 里没有这条同进程路径：
 * 生产只走 {@link RocketMqOutboxPublisher}。放在测试而不是生产、用
 * {@code @ConditionalOnProperty(transport=in-process)} 开关，是为了让集成测试能断言
 * “投递了什么”而不用真发 MQ——真发的话消费是异步的，而且同 JVM 的 listener
 * 会自己消费自己刚发的消息。
 */
@Component
@ConditionalOnProperty(name = "cabinet.outbox.transport", havingValue = "in-process")
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
