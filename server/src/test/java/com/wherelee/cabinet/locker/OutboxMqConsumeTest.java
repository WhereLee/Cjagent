package com.wherelee.cabinet.locker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.locker.api.CompartmentAnomaly;
import com.wherelee.cabinet.locker.api.DeadLetterPort;
import com.wherelee.cabinet.locker.api.OutboxPort.WorkOrderAnomalyPayload;
import com.wherelee.cabinet.locker.infra.outbox.WorkOrderAnomalyConsumer;
import com.wherelee.cabinet.locker.infra.outbox.WorkOrderDeadLetterConsumer;
import com.wherelee.cabinet.servicedesk.api.WorkOrderPort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * MQ 消费侧的四条契约。
 *
 * <p><b>为什么不真发 MQ</b>：同一个 JVM 里的 {@code @RocketMQMessageListener} 会立刻消费
 * 自己刚发的消息，于是"手工再调一次消费者"变成第二次投递、被幂等跳过——症状是单跑绿、连跑红。
 * 所以这里直接 {@code new} 消费者、手工构造 {@code MessageExt}，测的才是消费者本身。
 *
 * <p>直接 new 还有个好处：集成测试的 {@code cabinet.outbox.transport=in-process} 下
 * 消费者 bean 本来就不装配，这里绕开了装配条件，而不是为了测试去改生产配置。
 *
 * <p>断言用<b>相对增量</b>而不是绝对条数：格口是共享资源，别的用例可能已经在这个格口上开过单。
 */
@SpringBootTest
@ActiveProfiles({"dev", "integration"})
@Tag("integration")
@Sql(statements = "delete from biz_dead_letter")
class OutboxMqConsumeTest {

    @Autowired
    private WorkOrderPort workOrders;

    @Autowired
    private DeadLetterPort deadLetters;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ObjectMapper json;

    private JdbcTemplate jdbc;
    private Long tenantId;
    private Long slotId;

    private JdbcTemplate jdbc() {
        if (jdbc == null) {
            // 与 WorkOrderFlowTest 同一写法：注入 DataSource 自己 new，
            // 不按类型注 JdbcTemplate（容器里有两个数据源时按类型注会注错）
            jdbc = new JdbcTemplate(dataSource);
        }
        return jdbc;
    }

    @BeforeEach
    void pickSlot() {
        // 从格口反查它的租户，而不是分别取“第一个租户”和“第一个格口”：
        // 两个独立查询很容易拿到不匹配的组合（租户 A 的 id + 租户 B 的格口），
        // 那时开单会因“格口不属于本租户”静默不生效，症状是断言拿到 0。
        slotId = jdbc().queryForObject("select id from biz_compartment limit 1", Long.class);
        assertNotNull(slotId, "库里至少要有一个格口");
        tenantId = jdbc().queryForObject(
                "select tenant_id from biz_compartment where id = ?", Long.class, slotId);
        assertNotNull(tenantId, "格口必须有所属租户");
    }

    private WorkOrderAnomalyConsumer consumer() {
        return new WorkOrderAnomalyConsumer(workOrders, json, new SimpleMeterRegistry());
    }

    private MessageExt anomalyMessage(String dedupKey) throws Exception {
        WorkOrderAnomalyPayload payload =
                new WorkOrderAnomalyPayload(slotId, CompartmentAnomaly.DOOR_OPEN, "门开着");
        MessageExt message = new MessageExt();
        message.setTopic("cabinet-outbox");
        message.setBody(json.writeValueAsBytes(payload));
        message.putUserProperty("tenantId", String.valueOf(tenantId));
        // KEYS 是 RocketMQ 的系统属性，只能用 setKeys；putUserProperty 会直接报错
        message.setKeys(dedupKey);
        message.setReconsumeTimes(0);
        return message;
    }

    private int openWorkOrders() {
        Integer n = jdbc().queryForObject(
                "select count(*) from biz_work_order where slot_id = ? and active_flag = 1",
                Integer.class, slotId);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("同一消息消费两次，不会多出第二张活动单")
    void duplicateConsumeOpensOnlyOneOrder() throws Exception {
        int before = openWorkOrders();
        MessageExt message = anomalyMessage("test-dup-" + UUID.randomUUID());
        WorkOrderAnomalyConsumer consumer = consumer();

        // 消费线程没有请求上下文，租户由消息头重建——onMessage 内部会做这件事
        TenantContext.clear();
        consumer.onMessage(message);
        int afterFirst = openWorkOrders();

        // 第一次消费不断言 +1：这个格口在别的用例里可能已经有同类型活动单，
        // uk_work_order_open 会让本次合并到那张（findOpen 回读并返回），那是正确行为。
        assertTrue(afterFirst >= before, "消费一次不该让活动单变少");

        // 本条用例真正要验的是幂等：第二次投递不能再多出一张
        consumer.onMessage(message);
        assertEquals(afterFirst, openWorkOrders(), "重复投递不能再开第二张");
    }

    @Test
    @DisplayName("BizException 被吞掉并 ACK：重试无意义的失败不该占满 16 次重试预算")
    void bizExceptionIsAckedNotRetried() throws Exception {
        WorkOrderPort failing = mock(WorkOrderPort.class);
        // openForAnomaly 返回 void，只能用 doThrow；when(...) 在 void 方法上编译不过
        doThrow(new BizException(ResultCode.SYSTEM_ERROR, "工单唯一索引冲突但找不到未关闭的那张"))
                .when(failing).openForAnomaly(any(), any(), any());
        WorkOrderAnomalyConsumer consumer =
                new WorkOrderAnomalyConsumer(failing, json, new SimpleMeterRegistry());

        // 不抛 = ACK 掉，消息不会进重试队列；抛了这条用例就会红
        consumer.onMessage(anomalyMessage("test-bizex-" + UUID.randomUUID()));
        assertTrue(true, "吞掉即通过");
    }

    @Test
    @DisplayName("死信归档：DLQ 消费者把消息写进 biz_dead_letter，并记下重放目标")
    void deadLetterIsArchived() throws Exception {
        MessageExt message = anomalyMessage("test-dlq-" + UUID.randomUUID());
        message.setTopic("%DLQ%cabinet-outbox-workorder");
        message.setReconsumeTimes(16);

        new WorkOrderDeadLetterConsumer(deadLetters, "cabinet-outbox").onMessage(message);

        List<String> topics = jdbc().queryForList(
                "select topic from biz_dead_letter where msg_key = ?", String.class,
                message.getKeys());
        assertEquals(List.of("cabinet-outbox"), topics,
                "归档要记下**原来的**业务 topic，否则重放不知道该投回哪");
    }

    @Test
    @DisplayName("重放：投回原 topic 并把状态改成 REPLAYED；再点一次返回 false")
    void replayPublishesAndMarksReplayed() {
        // 用测试专用 topic：真投进业务 topic 会被 listener 消费，干扰别的用例
        String key = "test-replay-" + UUID.randomUUID();
        String payload = "{\"slotId\":" + slotId + ",\"anomaly\":\"DOOR_OPEN\",\"reason\":\"replay\"}";
        deadLetters.archive("cabinet-outbox-test", "WORK_ORDER_ANOMALY",
                "%DLQ%cabinet-outbox-workorder", tenantId, key, payload, "test", 16);

        Long id = jdbc().queryForObject(
                "select id from biz_dead_letter where msg_key = ?", Long.class, key);
        assertNotNull(id);

        assertTrue(deadLetters.replay(id), "OPEN 状态应当能重放");

        String status = jdbc().queryForObject(
                "select status from biz_dead_letter where id = ?", String.class, id);
        assertEquals("REPLAYED", status, "重放成功后状态要改，否则会被反复重放");

        assertFalse(deadLetters.replay(id), "已经重放过的再点一次要返回 false");
    }
}
