package com.wherelee.cabinet.servicedesk;

import com.wherelee.cabinet.assistant.api.WorkOrderBriefPort;
import com.wherelee.cabinet.servicedesk.api.WorkOrderConsolePort;
import com.wherelee.cabinet.servicedesk.app.WorkOrderService;
import com.wherelee.cabinet.common.context.TenantContext;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第 23 刀 B 端工单简报：AI 只归纳，判定权留在人手里。
 *
 * <p>专设断言两处：<b>相似已结单的召回边界</b>——同格口同类型且 RESOLVED 才算"以前怎么处理"，
 * 状态不对/类型不对/格口不对一律不算（agent 不许拿别的单充数的数据源头就在这）；
 * <b>桩模式装配可用</b>——AI 未开启时业务员拿到的是明确话术，不是异常。
 */
@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest(properties = {
        "cabinet.scheduler.poll-enabled=false",
})
@DisplayName("工单智能简报：AI 归纳，人判定")
class WorkOrderBriefFlowTest {

    private static final Long TENANT = 8165L;
    private static final Long OPERATOR = 99001L;

    @Autowired
    private DataSource dataSource;
    @Autowired
    private WorkOrderBriefPort briefs;
    @Autowired
    private WorkOrderService workOrders;

    private JdbcTemplate jdbc;
    private Long siteId;
    private Long slotId;
    private Long targetOrderId;
    private String suffix;

    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(dataSource);
        suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        siteId = ThreadLocalRandom.current().nextLong(82_6100_0000L, 89_9999_9999L);
        slotId = siteId + 2;
        jdbc.update("insert into biz_site (id, tenant_id, site_code, name, category, status, create_time, update_time, deleted) "
                + "values (?, ?, ?, '简报测试点位', 'MALL', 1, NOW(3), NOW(3), 0)",
                siteId, TENANT, "WB-" + suffix);
        jdbc.update("insert into biz_cabinet (id, tenant_id, site_id, cabinet_no, name, model_id, cabinet_status, online_state, create_time, update_time, deleted) "
                + "values (?, ?, ?, ?, '简报测试柜机', 90001, 'ENABLED', 'ONLINE', NOW(3), NOW(3), 0)",
                siteId + 1, TENANT, siteId, "CAB-WB-" + suffix);
        jdbc.update("insert into biz_compartment (id, tenant_id, cabinet_id, slot_no, size_type, status, "
                + "door_open_at, anomaly, current_order_id, create_time, update_time, deleted) "
                + "values (?, ?, ?, ?, 'SMALL', 'FREE', NULL, NULL, NULL, NOW(3), NOW(3), 0)",
                slotId, TENANT, siteId + 1, "W" + suffix.substring(0, 4));
        // 简报目标单：同格口的设备故障，PENDING 未关闭
        targetOrderId = siteId + 3;
        insertOrder(targetOrderId, "DEVICE_FAULT", "PENDING", null, null, 1);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from biz_work_order_event where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_work_order where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_compartment where cabinet_id = ?", siteId + 1);
        jdbc.update("delete from biz_cabinet where id = ?", siteId + 1);
        jdbc.update("delete from biz_site where tenant_id = ?", TENANT);
        TenantContext.clear();
    }

    /** 关闭态（RESOLVED/AUTO_CLOSED）的 active_flag 必须是 NULL：V17 唯一索引只对未关闭的 1 判重。 */
    private void insertOrder(Long id, String type, String status, String resultNote,
                             String closedAt, int activeFlag) {
        String slotNo = "W" + suffix.substring(0, 4);
        String activeSql = activeFlag == 1 ? "1" : "NULL";
        boolean hasClosed = closedAt != null;
        Object[] params = hasClosed
                ? new Object[]{id, TENANT, type, status, slotId, slotNo, "格口 " + slotNo + " " + type, resultNote, closedAt}
                : new Object[]{id, TENANT, type, status, slotId, slotNo, "格口 " + slotNo + " " + type, resultNote};
        jdbc.update("insert into biz_work_order (id, tenant_id, type, urgent, status, queue_side, slot_id, slot_no, "
                + "title, result_note, closed_at, active_flag, version, create_time, update_time, deleted) "
                + "values (?, ?, ?, 0, ?, 'ONLINE', ?, ?, ?, ?, " + (hasClosed ? "?" : "NULL")
                + ", " + activeSql + ", 0, NOW(3), NOW(3), 0)", params);
    }

    @Test
    @DisplayName("桩模式装配可用：AI 未开启时业务员拿到明确话术而不是异常")
    void stubBriefGuidesToExistingRecords() {
        WorkOrderBriefPort.Brief brief = TenantContext.callAs(TENANT,
                () -> briefs.brief(targetOrderId, OPERATOR));

        assertNotNull(brief);
        assertTrue(brief.summary().contains("未开启"), brief.summary());
        assertNotNull(brief.draftNote(), "草稿字段要在（即便桩态）");
        assertTrue(brief.similarCases().isEmpty(), "桩不给相似单");
    }

    @Test
    @DisplayName("相似已结单召回边界：同格口+同类型+RESOLVED 才算，其余一律不算")
    void similarResolvedHitsOnlyExactMatches() {
        // 命中：同格口同类型已处理
        insertOrder(siteId + 4, "DEVICE_FAULT", "RESOLVED",
                "电话联系后用户重新插拔卡扣，门恢复；柜内无物", "2026-10-10", 0);
        // 干扰：同格口同类型但不是人工处理（AUTO_CLOSED 是门关自愈留底，不算"以前怎么处理的"；
        // 同格同类型未关闭只能有一张——V17 唯一索引，所以非 RESOLVED 历史态只能用它）
        insertOrder(siteId + 5, "DEVICE_FAULT", "AUTO_CLOSED", null, null, 0);
        // 干扰：同格口但不同类型
        insertOrder(siteId + 6, "ITEM_LEFT", "RESOLVED", "不该被召回的不同类型", "2026-10-11", 0);
        // 干扰：同类型但不同格口
        Long otherSlot = slotId + 10;
        jdbc.update("insert into biz_compartment (id, tenant_id, cabinet_id, slot_no, size_type, status, "
                        + "door_open_at, anomaly, current_order_id, create_time, update_time, deleted) "
                        + "values (?, ?, ?, ?, 'SMALL', 'FREE', NULL, NULL, NULL, NOW(3), NOW(3), 0)",
                otherSlot, TENANT, siteId + 1, "WX" + otherSlot.toString().substring(otherSlot.toString().length() - 3));
        jdbc.update("insert into biz_work_order (id, tenant_id, type, urgent, status, queue_side, slot_id, slot_no, "
                + "title, result_note, closed_at, active_flag, version, create_time, update_time, deleted) "
                + "values (?, ?, 'DEVICE_FAULT', 0, 'RESOLVED', 'ONLINE', ?, ?, '别格口的同类型', '不该被召回的别的格口', '2026-10-12', 0, 0, NOW(3), NOW(3), 0)",
                siteId + 7, TENANT, otherSlot, "WX" + otherSlot.toString().substring(otherSlot.toString().length() - 3));

        List<WorkOrderConsolePort.SimilarCase> cases = TenantContext.callAs(TENANT,
                () -> workOrders.similarResolved(targetOrderId, 3));

        assertEquals(1, cases.size(), "只召回精确匹配的那一张：" + cases.size());
        assertEquals("电话联系后用户重新插拔卡扣，门恢复；柜内无物", cases.get(0).resultNote(), "结论要原文");
        assertEquals("2026-10-10", cases.get(0).resolvedAt(), "完结日期来自 closed_at");
    }
}
