package com.wherelee.cabinet;

import com.wherelee.cabinet.application.workorder.WorkOrderTriageService;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.domain.enums.WorkOrderCategory;
import com.wherelee.cabinet.domain.enums.WorkOrderImpact;
import com.wherelee.cabinet.domain.enums.WorkOrderIntent;
import com.wherelee.cabinet.domain.enums.WorkOrderPriority;
import com.wherelee.cabinet.domain.enums.WorkOrderType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 分诊规则：intent → category/queue/priority/SLA；未命中进人工分拣；租户规则覆盖默认值。 */
@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest(properties = {
        "cabinet.scheduler.poll-enabled=false",
})
@DisplayName("智能工单分诊：只认事实，不硬派")
class WorkOrderTriageTest {

    private static final Long TENANT = 8164L;

    @Autowired
    private WorkOrderTriageService triage;
    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("delete from biz_triage_rule where tenant_id = ?", TENANT);
        triage.reload();
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from biz_triage_rule where tenant_id = ?", TENANT);
        triage.reload();
        TenantContext.clear();
    }

    @Test
    @DisplayName("门打不开：默认落线上门故障，P2，带响应/解决时限")
    void doorNotOpenMapsToOnlineDoorFault() {
        WorkOrderTriageService.Result result = TenantContext.callAs(TENANT,
                () -> triage.triage(WorkOrderIntent.DOOR_NOT_OPEN, WorkOrderTriageService.Facts.none()));

        assertEquals(WorkOrderCategory.DOOR_FAULT, result.category());
        assertEquals(WorkOrderType.DEVICE_FAULT, result.type());
        assertEquals("ONLINE", result.queueSide());
        assertEquals(WorkOrderPriority.P2, result.priority());
        assertEquals(WorkOrderImpact.SINGLE, result.impact());
        assertTrue(result.responseMinutes() > 0 && result.resolveMinutes() >= result.responseMinutes());
    }

    @Test
    @DisplayName("整柜影响：同一现象直接拉到 P1/CABINET，不被默认 P2 压住")
    void cabinetImpactRaisesPriority() {
        WorkOrderTriageService.Result result = TenantContext.callAs(TENANT,
                () -> triage.triage(WorkOrderIntent.DOOR_NOT_OPEN,
                        new WorkOrderTriageService.Facts(false, true, false, false)));

        assertEquals(WorkOrderPriority.P1, result.priority());
        assertEquals(WorkOrderImpact.CABINET, result.impact());
    }

    @Test
    @DisplayName("同一现象不同事实：柜机离线时门打不开改判设备故障/整柜影响，不再按单格门故障派")
    void offlineCabinetChangesCategoryForSameIntent() {
        WorkOrderTriageService.Result online = TenantContext.callAs(TENANT,
                () -> triage.triage(WorkOrderIntent.DOOR_NOT_OPEN, WorkOrderTriageService.Facts.none()));
        WorkOrderTriageService.Result offline = TenantContext.callAs(TENANT,
                () -> triage.triage(WorkOrderIntent.DOOR_NOT_OPEN,
                        new WorkOrderTriageService.Facts(true, false, false, false)));

        assertEquals(WorkOrderCategory.DOOR_FAULT, online.category());
        assertEquals(WorkOrderCategory.DEVICE_OFFLINE, offline.category());
        assertEquals(WorkOrderType.DEVICE_FAULT, offline.type());
        assertEquals("ONLINE", offline.queueSide());
        assertEquals(WorkOrderPriority.P1, offline.priority());
        assertEquals(WorkOrderImpact.CABINET, offline.impact());
    }

    @Test
    @DisplayName("现象说不清：进 CUSTOMER_SERVICE + UNSORTED，不硬派给线上/线下")
    void unknownIntentFallsBackToHumanSorting() {
        WorkOrderTriageService.Result result = TenantContext.callAs(TENANT,
                () -> triage.triage(WorkOrderIntent.OTHER, WorkOrderTriageService.Facts.none()));

        assertEquals(WorkOrderCategory.UNSORTED, result.category());
        assertEquals("CUSTOMER_SERVICE", result.queueSide());
        assertEquals("CUSTOMER_SERVICE", result.assigneeGroup());
        assertEquals(WorkOrderPriority.P3, result.priority());
    }

    @Test
    @DisplayName("租户规则覆盖默认值：改版本后 reload 生效，不需发版")
    void tenantRuleOverridesDefault() {
        long id = ThreadLocalRandom.current().nextLong(83_6400_0000L, 89_9999_9999L);
        jdbc.update("insert into biz_triage_rule (id, tenant_id, rule_code, intent, category, queue_side, "
                        + "assignee_group, priority, impact, urgency, response_minutes, resolve_minutes, "
                        + "version, enabled, create_time, update_time, deleted) "
                        + "values (?, ?, 'R-TEST', 'DOOR_NOT_OPEN', 'ITEM_DISPUTE', 'FIELD', "
                        + "'FIELD_OPS', 'P1', 'SINGLE', 'HIGH', 5, 60, 2, 1, NOW(3), NOW(3), 0)",
                id, TENANT);
        triage.reload();

        WorkOrderTriageService.Result result = TenantContext.callAs(TENANT,
                () -> triage.triage(WorkOrderIntent.DOOR_NOT_OPEN, WorkOrderTriageService.Facts.none()));

        assertEquals(WorkOrderCategory.ITEM_DISPUTE, result.category());
        assertEquals("FIELD", result.queueSide());
        assertEquals("FIELD_OPS", result.assigneeGroup());
        assertEquals(WorkOrderPriority.P1, result.priority());
        assertEquals(2, result.ruleVersion());
        assertEquals(5, result.responseMinutes());
    }
}
