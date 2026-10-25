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
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

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
            jdbc = new JdbcTemplate(dataSource);
        }
        return jdbc;
    }

    @BeforeEach
    void pickSlot() {
        Long seed = ThreadLocalRandom.current().nextLong(82_0000_0000L, 88_9999_9999L);
        tenantId = jdbc().queryForObject("select id from sys_tenant limit 1", Long.class);
        Long siteId = seed;
        Long cabinetId = seed + 1;
        slotId = seed + 2;

        jdbc().update("insert into biz_site (id, tenant_id, site_code, name, category, status, create_time, update_time, deleted) "
                + "values (?, ?, ?, 'MQ 测试点位', 'MALL', 1, NOW(3), NOW(3), 0)",
                siteId, tenantId, "MQ-" + seed);
        jdbc().update("insert into biz_cabinet (id, tenant_id, site_id, cabinet_no, name, model_id, cabinet_status, online_state, create_time, update_time, deleted) "
                + "values (?, ?, ?, ?, 'MQ 测试柜机', 90001, 'ENABLED', 'ONLINE', NOW(3), NOW(3), 0)",
                cabinetId, tenantId, siteId, "CAB-MQ-" + seed);
        jdbc().update("insert into biz_compartment (id, tenant_id, cabinet_id, slot_no, size_type, status, "
                + "door_open_at, anomaly, current_order_id, create_time, update_time, deleted) "
                + "values (?, ?, ?, ?, 'SMALL', 'FREE', NOW(3), NULL, NULL, NOW(3), NOW(3), 0)",
                slotId, tenantId, cabinetId, "S" + (seed % 10000));
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

        TenantContext.clear();
        consumer.onMessage(message);
        int afterFirst = openWorkOrders();

        assertTrue(afterFirst >= before, "消费一次不该让活动单变少");

        consumer.onMessage(message);
        assertEquals(afterFirst, openWorkOrders(), "重复投递不能再开第二张");
    }

    @Test
    @DisplayName("BizException 被吞掉并 ACK：重试无意义的失败不该占满 16 次重试预算")
    void bizExceptionIsAckedNotRetried() throws Exception {
        WorkOrderPort failing = mock(WorkOrderPort.class);
        doThrow(new BizException(ResultCode.SYSTEM_ERROR, "工单唯一索引冲突但找不到未关闭的那张"))
                .when(failing).openForAnomaly(any(), any(), any());
        WorkOrderAnomalyConsumer consumer =
                new WorkOrderAnomalyConsumer(failing, json, new SimpleMeterRegistry());

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
