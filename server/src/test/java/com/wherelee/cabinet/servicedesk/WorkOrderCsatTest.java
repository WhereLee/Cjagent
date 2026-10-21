package com.wherelee.cabinet.servicedesk;

import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.servicedesk.api.WorkOrderCategory;
import com.wherelee.cabinet.servicedesk.api.WorkOrderConsolePort;
import com.wherelee.cabinet.servicedesk.api.WorkOrderCsatPort;
import com.wherelee.cabinet.servicedesk.api.WorkOrderImpact;
import com.wherelee.cabinet.servicedesk.api.WorkOrderIntent;
import com.wherelee.cabinet.servicedesk.api.WorkOrderKind;
import com.wherelee.cabinet.servicedesk.api.WorkOrderPriority;
import com.wherelee.cabinet.servicedesk.api.WorkOrderSource;
import com.wherelee.cabinet.servicedesk.api.WorkOrderType;
import com.wherelee.cabinet.servicedesk.api.WorkOrderUrgency;
import com.wherelee.cabinet.servicedesk.app.WorkOrderCsatService;
import com.wherelee.cabinet.servicedesk.app.WorkOrderService;
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
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 工单满意度：只有本人、只有“用户确认解决”的单、一单一次。
 *
 * <p>评价只是事实记录：不改变状态、不影响 SLA；重复提交由唯一索引兜底。
 */
@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest(properties = "cabinet.scheduler.poll-enabled=false")
@DisplayName("工单满意度：确认解决后一次评价")
class WorkOrderCsatTest {

    private static final Long TENANT = 8172L;
    private static final Long CUSTOMER = 81720001L;
    private static final Long OTHER_CUSTOMER = 81720002L;
    private static final Long OPERATOR = 81720003L;

    @Autowired
    private WorkOrderService workOrders;
    @Autowired
    private WorkOrderCsatService csats;
    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private Long siteId;
    private Long cabinetId;
    private Long slotId;

    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("delete from biz_work_order_csat where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_work_order_event where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_delay_task where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_work_order where tenant_id = ?", TENANT);

        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        siteId = ThreadLocalRandom.current().nextLong(81_7200_0000L, 89_9999_9999L);
        cabinetId = siteId + 1;
        slotId = siteId + 2;
        jdbc.update("insert into biz_site (id, tenant_id, site_code, name, category, status, "
                        + "create_time, update_time, deleted) values (?, ?, ?, '满意度测试点位', 'MALL', 1, NOW(3), NOW(3), 0)",
                siteId, TENANT, "CSAT-" + suffix);
        jdbc.update("insert into biz_cabinet (id, tenant_id, site_id, cabinet_no, name, model_id, "
                        + "cabinet_status, online_state, create_time, update_time, deleted) "
                        + "values (?, ?, ?, ?, '满意度测试柜机', 90001, 'ENABLED', 'ONLINE', NOW(3), NOW(3), 0)",
                cabinetId, TENANT, siteId, "CAB-CSAT-" + suffix);
        jdbc.update("insert into biz_compartment (id, tenant_id, cabinet_id, slot_no, size_type, status, "
                        + "create_time, update_time, deleted) values (?, ?, ?, 'S01', 'SMALL', 'FREE', NOW(3), NOW(3), 0)",
                slotId, TENANT, cabinetId);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from biz_work_order_csat where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_work_order_event where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_delay_task where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_work_order where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_compartment where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_cabinet where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_site where tenant_id = ?", TENANT);
    }

    @Test
    @DisplayName("确认解决后可评一次；详情带回评分；重复提交被拒")
    void rateOnceAfterUserConfirmed() {
        Long id = openOrder();
        assertThrows(BizException.class,
                () -> TenantContext.callAs(TENANT, () -> csats.submit(CUSTOMER, id, 5, null)),
                "还没确认解决就评价必须被拒");

        TenantContext.runAs(TENANT, () -> workOrders.confirmResolved(id, CUSTOMER, "已经好了"));
        WorkOrderCsatPort.CsatView view = TenantContext.callAs(TENANT,
                () -> csats.submit(CUSTOMER, id, 5, "师傅来得很快"));
        assertEquals(5, view.score());
        assertEquals("师傅来得很快", view.comment());

        WorkOrderConsolePort.CustomerView mine = TenantContext.callAs(TENANT,
                () -> workOrders.myOrder(id, CUSTOMER));
        assertEquals(5, mine.csatScore(), "详情要带回评分，前端才能显示已评价");

        assertThrows(BizException.class,
                () -> TenantContext.callAs(TENANT, () -> csats.submit(CUSTOMER, id, 4, null)),
                "一单只能评一次");
    }

    @Test
    @DisplayName("别人的单不能评价；非法分值被拒")
    void onlyOwnerAndValidScore() {
        Long id = openOrder();
        TenantContext.runAs(TENANT, () -> workOrders.confirmResolved(id, CUSTOMER, "好了"));
        assertThrows(BizException.class,
                () -> TenantContext.callAs(TENANT, () -> csats.submit(OTHER_CUSTOMER, id, 5, null)),
                "不能替别人的单评价");
        assertThrows(BizException.class,
                () -> TenantContext.callAs(TENANT, () -> csats.submit(CUSTOMER, id, 0, null)),
                "0 星不是合法评价");
        assertNull(TenantContext.callAs(TENANT, () -> csats.byOrder(id)), "被拒的提交不能留下记录");
    }

    private Long openOrder() {
        return TenantContext.callAs(TENANT, () -> workOrders.open(new WorkOrderConsolePort.NewOrderV2(
                TENANT, WorkOrderKind.REQUEST, WorkOrderType.DEVICE_FAULT, WorkOrderSource.AI,
                WorkOrderIntent.DOOR_NOT_OPEN, WorkOrderCategory.DOOR_FAULT, false,
                WorkOrderPriority.P2, WorkOrderImpact.SINGLE, WorkOrderUrgency.HIGH,
                "ONLINE", WorkOrderService.SIDE_ONLINE,
                siteId, cabinetId, slotId, "S01",
                "CSAT-SO-" + slotId, null, CUSTOMER, "13800001234",
                "满意度测试点位", "CAB-CSAT", "满意度测试工单", "门=已关",
                LocalDateTime.now().plusMinutes(30), LocalDateTime.now().plusMinutes(30),
                LocalDateTime.now().plusMinutes(120), null, null)));
    }
}
