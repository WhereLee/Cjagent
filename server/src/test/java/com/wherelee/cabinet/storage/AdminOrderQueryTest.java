package com.wherelee.cabinet.storage;

import com.wherelee.cabinet.storage.api.AdminOrderConsolePort;
import com.wherelee.cabinet.common.api.PageQuery;
import com.wherelee.cabinet.common.api.PageResult;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.enums.OrderStatus;
import com.wherelee.cabinet.bff.admin.order.AdminOrderController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 后台订单查询：只读、按租户、带齐事实。
 *
 * <p>这组用例盯三件事：
 * ① 客服拿订单号或手机号能查到，查不到是空页而不是报错；
 * ② 别的租户拿同一单号查不到（租户隔离不是靠前端）；
 * ③ 详情里的钱、设备动作、关联工单不是摆设——它们是“取不出来”这个问题的判据。
 */
@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest(properties = {
        "cabinet.scheduler.poll-enabled=false",
})
@DisplayName("后台订单查询：一张单的全部事实，只读")
class AdminOrderQueryTest {

    private static final Long TENANT = 8171L;
    private static final Long OTHER_TENANT = 8172L;
    private static final String PHONE = "13900001111";

    @Autowired
    private DataSource dataSource;
    @Autowired
    private AdminOrderConsolePort orders;

    private JdbcTemplate jdbc;
    private Long siteId;
    private Long cabinetId;
    private Long slotId;
    private Long customerId;
    private String activeOrderNo;
    private String closedOrderNo;

    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(dataSource);
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        long base = ThreadLocalRandom.current().nextLong(81_7100_0000L, 89_9999_9999L);
        siteId = base;
        cabinetId = base + 1;
        slotId = base + 2;
        customerId = base + 3;
        long activeOrderId = base + 4;
        long closedOrderId = base + 5;
        activeOrderNo = "SO-AD-" + suffix + "-A";
        closedOrderNo = "SO-AD-" + suffix + "-B";

        jdbc.update("insert into biz_site (id, tenant_id, site_code, name, category, status, create_time, update_time, deleted) "
                        + "values (?, ?, ?, '订单查询测试点位', 'MALL', 1, NOW(3), NOW(3), 0)",
                siteId, TENANT, "AD-" + suffix);
        jdbc.update("insert into biz_cabinet (id, tenant_id, site_id, cabinet_no, name, model_id, cabinet_status, online_state, create_time, update_time, deleted) "
                        + "values (?, ?, ?, ?, '订单查询测试柜机', 90001, 'ENABLED', 'ONLINE', NOW(3), NOW(3), 0)",
                cabinetId, TENANT, siteId, "CAB-AD-" + suffix);
        jdbc.update("insert into biz_compartment (id, tenant_id, cabinet_id, slot_no, size_type, status, "
                        + "door_open_at, anomaly, current_order_id, create_time, update_time, deleted) "
                        + "values (?, ?, ?, ?, 'SMALL', 'OCCUPIED', NOW(3), NULL, ?, NOW(3), NOW(3), 0)",
                slotId, TENANT, cabinetId, "S" + suffix.substring(0, 4), activeOrderId);
        jdbc.update("insert into biz_customer (id, tenant_id, open_id, nickname, phone, status, register_time, create_time, update_time, deleted) "
                        + "values (?, ?, ?, '订单客户', ?, 1, NOW(3), NOW(3), NOW(3), 0)",
                customerId, TENANT, "ad-" + suffix, PHONE);

        // 进行中那张：今天创建、已开始计费、预收 400 点，带定价快照（账单可重算）
        String snapshot = "{\"unitPointsPerHour\":200,\"freeMinutes\":30,\"dailyCapHours\":10,\"capDays\":2}";
        jdbc.update("insert into biz_storage_order (id, tenant_id, order_no, site_id, cabinet_id, slot_id, size_type, "
                        + "customer_id, status, voucher_code, temp_open_count, deposit_points, frozen_points, "
                        + "settled_points, arrears_points, prepay_points, started_at, pricing_snapshot, "
                        + "create_time, update_time, deleted, active_flag) "
                        + "values (?, ?, ?, ?, ?, ?, 'SMALL', ?, 'ACTIVE', ?, 1, 0, 0, 0, 0, 400, "
                        + "DATE_SUB(NOW(3), INTERVAL 1 HOUR), ?, NOW(3), NOW(3), 0, 1)",
                activeOrderId, TENANT, activeOrderNo, siteId, cabinetId, slotId, customerId,
                suffix.substring(0, 4), snapshot);
        // 三天前已结束那张：用来验证状态过滤与时间范围
        jdbc.update("insert into biz_storage_order (id, tenant_id, order_no, site_id, cabinet_id, slot_id, size_type, "
                        + "customer_id, status, voucher_code, temp_open_count, deposit_points, frozen_points, "
                        + "settled_points, arrears_points, prepay_points, started_at, finished_at, "
                        + "create_time, update_time, deleted, active_flag) "
                        + "values (?, ?, ?, ?, ?, ?, 'SMALL', ?, 'CLOSED', ?, 0, 0, 0, 300, 0, 400, "
                        + "DATE_SUB(NOW(3), INTERVAL 3 DAY), DATE_SUB(NOW(3), INTERVAL 3 DAY), "
                        + "DATE_SUB(NOW(3), INTERVAL 3 DAY), NOW(3), 0, NULL)",
                closedOrderId, TENANT, closedOrderNo, siteId, cabinetId, slotId, customerId,
                suffix.substring(4, 8));

        // 设备动作：客服判断“门到底开没开”靠这一行
        jdbc.update("insert into biz_device_command (id, tenant_id, order_id, cabinet_id, slot_id, request_id, "
                        + "action, status, retry_count, sent_at, acked_at, create_time, update_time, deleted) "
                        + "values (?, ?, ?, ?, ?, ?, 'OPEN', 'SUCCEEDED', 1, NOW(3), NOW(3), NOW(3), NOW(3), 0)",
                base + 6, TENANT, activeOrderId, cabinetId, slotId, "ad-cmd-" + suffix);
        // 关联工单：让客服先看有没有人已经在处理
        jdbc.update("insert into biz_work_order (id, tenant_id, kind, source, status, queue_side, active_flag, "
                        + "priority, category, storage_id, order_no, title, create_time, update_time, deleted) "
                        + "values (?, ?, 'INCIDENT', 'SYSTEM', 'PENDING', 'ONLINE', 1, 'P3', 'DOOR_FAULT', ?, ?, "
                        + "'订单客户报障', NOW(3), NOW(3), 0)",
                base + 7, TENANT, activeOrderId, activeOrderNo);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from biz_work_order where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_device_command where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_storage_order where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_compartment where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_cabinet where id = ?", cabinetId);
        jdbc.update("delete from biz_site where id = ?", siteId);
        jdbc.update("delete from biz_customer where id = ?", customerId);
        TenantContext.clear();
    }

    @Test
    @DisplayName("按订单号与手机号都能查到；查不到是空页，不是报错")
    void listByOrderNoAndPhone() {
        PageResult<AdminOrderConsolePort.OrderRow> byNo = TenantContext.callAs(TENANT,
                () -> orders.list(activeOrderNo, null, null, null, null, new PageQuery()));
        assertEquals(1, byNo.total());
        assertEquals(activeOrderNo, byNo.records().get(0).orderNo());
        assertEquals("订单客户", byNo.records().get(0).customerName());
        assertEquals("订单查询测试点位", byNo.records().get(0).siteName());
        assertEquals("CAB-AD-", byNo.records().get(0).cabinetNo().substring(0, 7));

        PageResult<AdminOrderConsolePort.OrderRow> byPhone = TenantContext.callAs(TENANT,
                () -> orders.list(null, PHONE, null, null, null, new PageQuery()));
        assertEquals(2, byPhone.total(), "同一手机号的两张单都要能被找出来");

        PageResult<AdminOrderConsolePort.OrderRow> none = TenantContext.callAs(TENANT,
                () -> orders.list(null, "13000000000", null, null, null, new PageQuery()));
        assertEquals(0, none.total());
        assertTrue(none.records().isEmpty());
    }

    @Test
    @DisplayName("状态与创建时间范围能过滤：查旧单时不会把今天的单混进来")
    void statusAndTimeFilter() {
        PageResult<AdminOrderConsolePort.OrderRow> closed = TenantContext.callAs(TENANT,
                () -> orders.list(null, null, OrderStatus.CLOSED, null, null, new PageQuery()));
        assertEquals(1, closed.total());
        assertEquals(closedOrderNo, closed.records().get(0).orderNo());

        PageResult<AdminOrderConsolePort.OrderRow> yesterday = TenantContext.callAs(TENANT,
                () -> orders.list(null, null, null, LocalDate.now().minusDays(1), LocalDate.now(),
                        new PageQuery()));
        assertEquals(1, yesterday.total(), "三天前那张不该落进‘昨天到今天’的范围");
        assertEquals(activeOrderNo, yesterday.records().get(0).orderNo());
    }

    @Test
    @DisplayName("租户隔离：别的租户拿同一单号查不到")
    void tenantIsolation() {
        assertThrows(BizException.class,
                () -> TenantContext.callAs(OTHER_TENANT, () -> orders.detail(activeOrderNo)));
        PageResult<AdminOrderConsolePort.OrderRow> other = TenantContext.callAs(OTHER_TENANT,
                () -> orders.list(null, PHONE, null, null, null, new PageQuery()));
        assertEquals(0, other.total());
    }

    @Test
    @DisplayName("详情带齐账单、设备动作与关联工单；证据为空时返回空列表")
    void detailCarriesBillCommandsAndWorkOrders() {
        AdminOrderConsolePort.OrderDetail detail = TenantContext.callAs(TENANT,
                () -> orders.detail(activeOrderNo));

        // 客服要能回拨 → 有 customer:phone:view 的账号拿到明文（V40 授给了两个作业角色）；
        // 这里是无认证上下文的服务层直调，所以走默认拒绝脱敏
        assertEquals(com.wherelee.cabinet.common.mask.MaskType.PHONE.mask(PHONE),
                detail.customerPhone());
        assertEquals("ACTIVE", detail.row().status());
        assertEquals(400, detail.bill().prepayPoints(), "预收点数来自这张单本身");

        assertEquals(1, detail.commands().size());
        assertEquals("OPEN", detail.commands().get(0).action());
        assertEquals("SUCCEEDED", detail.commands().get(0).state());

        assertTrue(detail.evidence().isEmpty(), "这张单没有物检测量，返回空列表而不是报错");

        assertEquals(1, detail.workOrders().size());
        assertEquals("PENDING", detail.workOrders().get(0).status());
        assertEquals("DOOR_FAULT", detail.workOrders().get(0).category());
    }

    @Test
    @DisplayName("两个读端点都挂 order:list，且返回应用层视图而不是实体")
    void readEndpointsCarryOrderPermission() {
        Method[] methods = AdminOrderController.class.getDeclaredMethods();
        int guarded = 0;
        for (Method method : methods) {
            PreAuthorize pre = method.getAnnotation(PreAuthorize.class);
            if (pre != null) {
                guarded++;
                assertEquals("hasAuthority('order:list')", pre.value(),
                        method.getName() + " 必须挂订单查询权限点");
            }
        }
        assertEquals(2, guarded, "列表与详情两个端点都要独立鉴权");

        String listReturn = java.util.Arrays.stream(methods)
                .filter(m -> m.getName().equals("list")).findFirst().orElseThrow()
                .getGenericReturnType().getTypeName();
        assertTrue(listReturn.contains("AdminOrderConsolePort$OrderRow"),
                "列表必须返回应用层视图：" + listReturn);
        assertFalse(listReturn.contains("BizStorageOrder"), "接口层不得回传实体");
    }
}
