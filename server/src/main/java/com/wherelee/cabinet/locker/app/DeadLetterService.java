package com.wherelee.cabinet.locker.app;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wherelee.cabinet.common.api.PageQuery;
import com.wherelee.cabinet.common.api.PageResult;
import com.wherelee.cabinet.domain.entity.BizDeadLetter;
import com.wherelee.cabinet.infrastructure.mapper.BizDeadLetterMapper;
import com.wherelee.cabinet.locker.api.DeadLetterPort;
import com.wherelee.cabinet.locker.infra.RocketMqOutboxPublisher;
import com.wherelee.cabinet.platform.web.PageSupport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Set;

@Service
public class DeadLetterService implements DeadLetterPort {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);

    private static final String NO_ROOT_CAUSE = "重试耗尽（MQ 不保留原始异常）";

    private static final Set<String> SORTABLE = Set.of("receivedAt", "reconsumeTimes", "status");

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
        log.error("MQ 死信归档 dlq={} key={} times={} error={}", dlqTopic, msgKey, reconsumeTimes, error);
    }

    @Override
    public boolean replay(Long id) {
        BizDeadLetter row = mapper.selectById(id);
        if (row == null || !BizDeadLetter.STATUS_OPEN.equals(row.getStatus())) {
            return false;
        }
        if (row.getTenantId() == null) {
            notReplayable.increment();
            log.warn("死信缺少租户信息，不可重放 id={} dlq={}", id, row.getDlqTopic());
            return false;
        }

        Message<String> message = MessageBuilder.withPayload(row.getPayload())
                .setHeader(RocketMqOutboxPublisher.HEADER_TENANT_ID, String.valueOf(row.getTenantId()))
                .setHeader(RocketMQHeaders.KEYS, row.getMsgKey())
                .build();
        rocketMQTemplate.syncSend(row.getTopic() + ":" + row.getTag(), message);

        if (mapper.markReplayed(id) != 1) {
            return false;
        }
        replayed.increment();
        log.info("死信已重放 id={} target={}:{}", id, row.getTopic(), row.getTag());
        return true;
    }

    @Override
    public PageResult<DeadLetterView> list(String status, PageQuery query) {
        Page<BizDeadLetter> page = PageSupport.toPage(query, SORTABLE);
        LambdaQueryWrapper<BizDeadLetter> wrapper = Wrappers.<BizDeadLetter>lambdaQuery()
                .eq(StringUtils.hasText(status), BizDeadLetter::getStatus, status);
        if (!StringUtils.hasText(query.getOrderBy())) {
            wrapper.orderByDesc(BizDeadLetter::getReceivedAt);
        }
        IPage<BizDeadLetter> selected = mapper.selectPage(page, wrapper);
        return PageSupport.toResult(selected, DeadLetterService::toView);
    }

    private static DeadLetterView toView(BizDeadLetter row) {
        return new DeadLetterView(row.getId(), row.getTopic(), row.getTag(), row.getDlqTopic(), row.getMsgKey(),
                row.getPayload(), row.getError(),
                row.getReconsumeTimes() == null ? 0 : row.getReconsumeTimes(),
                row.getStatus(), row.getReceivedAt(), row.getReplayedAt());
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
