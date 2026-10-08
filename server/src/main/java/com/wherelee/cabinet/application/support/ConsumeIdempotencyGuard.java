package com.wherelee.cabinet.application.support;

import com.wherelee.cabinet.domain.entity.BizMsgConsume;
import com.wherelee.cabinet.infrastructure.mapper.BizMsgConsumeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 消费幂等守卫：把"这条消息要不要真的执行业务"的判断收在一处。
 *
 * <p>三种结论：
 * <ul>
 *   <li>{@link Decision#FIRST} —— 首次或可重试（PENDING/FAILED），业务照常执行；</li>
 *   <li>{@link Decision#SKIP} —— 已 PROCESSED，重投，直接跳过；</li>
 * </ul>
 *
 * <p><b>这里存在一个无法靠本类消除的窄竞态</b>：两个副本并发进来，都读到"没有记录"，
 * 都判定 FIRST，然后一方插入成功、一方撞唯一键。撞键的一方仍然会执行业务——
 * 所以业务侧必须自己幂等（本项目落库靠 {@code uk_order_active_slot} + 订单号唯一索引）。
 * 我不在这里"顺手"把撞键当跳过处理：那会让"第一次真的没落库"的情况被误判成已完成，
 * 消息就此静默丢失。<b>宁可重复执行（有唯一索引挡着），不可漏执行。</b>
 */
@Service
public class ConsumeIdempotencyGuard {

    private static final Logger log = LoggerFactory.getLogger(ConsumeIdempotencyGuard.class);

    public enum Decision { FIRST, SKIP }

    private final BizMsgConsumeMapper mapper;

    public ConsumeIdempotencyGuard(BizMsgConsumeMapper mapper) {
        this.mapper = mapper;
    }

    public Decision begin(String topic, String msgKey, Long tenantId, String traceId) {
        String status = mapper.selectStatus(topic, msgKey);
        if (BizMsgConsume.STATUS_PROCESSED.equals(status)) {
            log.info("重复投递，跳过 topic={} key={}", topic, msgKey);
            return Decision.SKIP;
        }
        if (status == null) {
            try {
                mapper.insert(recordOf(topic, msgKey, tenantId, traceId));
            } catch (DuplicateKeyException e) {
                // 并发副本刚插入：重新读一次它的状态，而不是猜
                String current = mapper.selectStatus(topic, msgKey);
                if (BizMsgConsume.STATUS_PROCESSED.equals(current)) {
                    return Decision.SKIP;
                }
                log.info("并发首投已存在（{}），本次仍执行业务，由唯一索引保证不重复落库 topic={} key={}",
                        current, topic, msgKey);
            }
        }
        return Decision.FIRST;
    }

    public void markProcessed(String topic, String msgKey) {
        mapper.markStatus(topic, msgKey, BizMsgConsume.STATUS_PROCESSED, null, null);
    }

    public void markFailed(String topic, String msgKey, String error, String traceId) {
        mapper.markStatus(topic, msgKey, BizMsgConsume.STATUS_FAILED, trim(error), traceId);
    }

    private BizMsgConsume recordOf(String topic, String msgKey, Long tenantId, String traceId) {
        BizMsgConsume record = new BizMsgConsume();
        record.setTopic(topic);
        record.setMsgKey(msgKey);
        record.setTenantId(tenantId);
        record.setStatus(BizMsgConsume.STATUS_PENDING);
        record.setAttempt(1);
        record.setTraceId(traceId);
        return record;
    }

    /** last_error 列宽 255，堆栈不能整段塞进去。 */
    private String trim(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 255 ? value : value.substring(0, 255);
    }
}
