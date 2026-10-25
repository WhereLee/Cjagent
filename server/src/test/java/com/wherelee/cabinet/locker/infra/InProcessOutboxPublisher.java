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
            throw new IllegalStateException("outbox 载荷无法解析 id=" + row.getId(), e);
        }
    }
}
