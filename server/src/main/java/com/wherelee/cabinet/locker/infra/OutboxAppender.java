package com.wherelee.cabinet.locker.infra;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.domain.enums.OutboxEventType;
import com.wherelee.cabinet.infrastructure.mapper.BizOutboxMapper;
import com.wherelee.cabinet.locker.api.CompartmentAnomaly;
import com.wherelee.cabinet.locker.api.OutboxPort;
import com.wherelee.cabinet.notify.api.NoticeChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class OutboxAppender implements OutboxPort {

    private static final Logger log = LoggerFactory.getLogger(OutboxAppender.class);

    private final BizOutboxMapper outbox;
    private final ObjectMapper json;

    public OutboxAppender(BizOutboxMapper outbox, ObjectMapper json) {
        this.outbox = outbox;
        this.json = json;
    }

    @Override
    public void appendWorkOrderAnomaly(Long slotId, CompartmentAnomaly anomaly, String reason, String dedupKey) {
        append(OutboxEventType.WORK_ORDER_ANOMALY, dedupKey,
                new WorkOrderAnomalyPayload(slotId, anomaly, reason));
    }

    @Override
    public void appendOwnerNotice(NoticeChannel channel, String kind, String dedupKey, String content,
                                  Long customerId, Long workOrderId, String orderNo) {
        append(OutboxEventType.OWNER_NOTICE, dedupKey,
                new OwnerNoticePayload(channel, kind, dedupKey, content, customerId, workOrderId, orderNo));
    }

    private void append(OutboxEventType type, String dedupKey, Object payload) {
        Long tenantId = TenantContext.current();
        int inserted = outbox.append(IdWorker.getId(), tenantId, type.name(), dedupKey, toJson(payload));
        if (inserted == 0) {
            log.debug("同一事实已在 outbox 里，不重复入队 type={} dedupKey={}", type, dedupKey);
        }
    }

    private String toJson(Object payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("outbox 载荷序列化失败", e);
        }
    }
}
