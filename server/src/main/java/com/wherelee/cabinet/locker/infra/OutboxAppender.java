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

/**
 * {@link OutboxPort} 的实现：把事实写进 {@code biz_outbox}，与调用方的业务写在同一个事务里。
 *
 * <p><b>这里刻意不做任何投递</b>——投递交给 {@code OutboxRelay}。同一事务里只写一行本地表，
 * 所以它不会失败，也就不会把上游的事实带下水。原来那两处扇出是同步调工单与通知的，
 * 下游一抛异常就把共享事务标成 rollback-only，用户取件/上报跟着回滚。
 *
 * <p>为什么用 {@code IdWorker.getId()} 手写 ID：{@code BizOutboxMapper.append} 是手写 SQL，
 * 不走 MyBatis-Plus 的 {@code ASSIGN_ID} 生成，ID 必须显式给。
 */
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
            // 同一事实已经在表里（可能已投递成功、也可能已判死）：不重复入队，也不复活它
            log.debug("同一事实已在 outbox 里，不重复入队 type={} dedupKey={}", type, dedupKey);
        }
    }

    private String toJson(Object payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // record 的序列化不会失败；走到这里说明载荷里塞了不可序列化的东西，属编程错误，
            // 抛出去让它在开发期就炸，而不是写一行永远投不出去的事实
            throw new IllegalStateException("outbox 载荷序列化失败", e);
        }
    }
}
