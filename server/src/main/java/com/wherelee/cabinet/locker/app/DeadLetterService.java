package com.wherelee.cabinet.locker.app;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.wherelee.cabinet.domain.entity.BizDeadLetter;
import com.wherelee.cabinet.infrastructure.mapper.BizDeadLetterMapper;
import com.wherelee.cabinet.locker.api.DeadLetterPort;
import com.wherelee.cabinet.locker.infra.RocketMqOutboxPublisher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 死信的归档与重放。
 *
 * <p>"标准四件套"里，这个类承担两件（归档、重放），DLQ 消费者承担"把它交过来"，
 * 指标与 ERROR 日志承担"让它被看见"。
 *
 * <p>为什么重放要回到<b>原业务 topic</b>而不是重新投回死信：死信 topic 是隔离区，
 * 往里投等于把消息再埋一次。所以归档时把原 topic + tag 一起记下来了。
 */
@Service
public class DeadLetterService implements DeadLetterPort {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);

    /** DLQ 消息里没有原始异常（RocketMQ 不保留），只能记一句事实。 */
    private static final String NO_ROOT_CAUSE = "重试耗尽（MQ 不保留原始异常）";

    private final BizDeadLetterMapper mapper;
    private final RocketMQTemplate rocketMQTemplate;
    private final Counter archived;
    private final Counter replayed;
    private final Counter notReplayable;

    public DeadLetterService(BizDeadLetterMapper mapper, RocketMQTemplate rocketMQTemplate,
                             MeterRegistry meterRegistry) {
        this.mapper = mapper;
        this.rocketMQTemplate = rocketMQTemplate;
        this.archived = Counter.builder("cabinet.outbox.dead_letter").tag("phase", "archived")
                .description("进入死信并归档的消息数。非零就该有人去看 biz_dead_letter")
                .register(meterRegistry);
        this.replayed = Counter.builder("cabinet.outbox.dead_letter").tag("phase", "replayed")
                .description("人工重放成功的消息数")
                .register(meterRegistry);
        this.notReplayable = Counter.builder("cabinet.outbox.dead_letter").tag("phase", "not-replayable")
                .description("因缺少租户信息而无法重放的消息数")
                .register(meterRegistry);
    }

    @Override
    public void archive(String topic, String tag, String dlqTopic, Long tenantId,
                        String msgKey, String payload, String error, int reconsumeTimes) {
        mapper.append(IdWorker.getId(), tenantId, cut(topic, 128), cut(tag, 64), cut(dlqTopic, 128),
                cut(msgKey, 96), cut(payload, 2048),
                cut(error == null || error.isBlank() ? NO_ROOT_CAUSE : error, 255), reconsumeTimes);
        archived.increment();
        // 指标是给监控看的，这条 ERROR 是给人看的：带上 msgKey 才能在控制台按 KEYS 捞到原消息
        log.error("MQ 死信归档 dlq={} key={} times={} error={}", dlqTopic, msgKey, reconsumeTimes, error);
    }

    @Override
    public boolean replay(Long id) {
        BizDeadLetter row = mapper.selectById(id);
        if (row == null || !BizDeadLetter.STATUS_OPEN.equals(row.getStatus())) {
            return false;
        }
        if (row.getTenantId() == null) {
            // 坏消息：连租户都没有，重放回去消费侧也重建不出上下文，重放只会再进一次死信
            notReplayable.increment();
            log.warn("死信缺少租户信息，不可重放 id={} dlq={}", id, row.getDlqTopic());
            return false;
        }

        Message<String> message = MessageBuilder.withPayload(row.getPayload())
                .setHeader(RocketMqOutboxPublisher.HEADER_TENANT_ID, String.valueOf(row.getTenantId()))
                .setHeader(RocketMQHeaders.KEYS, row.getMsgKey())
                .build();
        rocketMQTemplate.syncSend(row.getTopic() + ":" + row.getTag(), message);

        // 投递成功后改状态；影响行数 0 说明并发点了两次，别人已经改过
        if (mapper.markReplayed(id) != 1) {
            return false;
        }
        replayed.increment();
        log.info("死信已重放 id={} target={}:{}", id, row.getTopic(), row.getTag());
        return true;
    }

    @Override
    public List<View> listOpen(int limit) {
        return mapper.listOpen(limit).stream()
                .map(r -> new View(r.getId(), r.getTopic(), r.getTag(), r.getMsgKey(), r.getError(),
                        r.getReconsumeTimes() == null ? 0 : r.getReconsumeTimes(),
                        r.getStatus(), r.getReceivedAt()))
                .toList();
    }

    /** 列宽都写死在表结构里；超长会被 MySQL 截断或直接报错，截在这里比让它报错好。 */
    private static String cut(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
