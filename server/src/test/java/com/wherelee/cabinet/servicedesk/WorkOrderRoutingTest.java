package com.wherelee.cabinet.servicedesk;

import com.wherelee.cabinet.servicedesk.app.WorkOrderRoutingService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 责任组 → 值班人：组内启用账号轮转，不建排班表。
 *
 * <p>轮转计数在 Redis：多实例部署时也必须保证“这一单给 A、下一单给 B”，
 * 进程内的计数器在集群里会各自为政。
 */
@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest(properties = {
        "cabinet.scheduler.poll-enabled=false",
})
@DisplayName("值班人路由：按组轮转、停用账号不参与")
class WorkOrderRoutingTest {

    private static final Long TENANT = 8180L;
    private static final Long ROLE_ID = 81800001L;
    private static final Long USER_A = 81800101L;
    private static final Long USER_B = 81800102L;

    @Autowired
    private WorkOrderRoutingService routing;
    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(dataSource);
        clean();
        jdbc.update("insert into sys_role (id, tenant_id, role_code, role_name, status, create_time, update_time, deleted) "
                + "values (?, ?, 'field_ops', '线下运维', 1, NOW(3), NOW(3), 0)", ROLE_ID, TENANT);
        insertUser(USER_A, "duty-a", 1);
        insertUser(USER_B, "duty-b", 1);
        jdbc.update("insert into sys_user_role (id, tenant_id, user_id, role_id, create_time, update_time, deleted) "
                + "values (81800201, ?, ?, ?, NOW(3), NOW(3), 0), (81800202, ?, ?, ?, NOW(3), NOW(3), 0)",
                TENANT, USER_A, ROLE_ID, TENANT, USER_B, ROLE_ID);
        TenantContext.runAs(TENANT, () -> {
            routing.resetRotation("FIELD_OPS");
            routing.resetRotation("ONLINE");
        });
    }

    @AfterEach
    void cleanup() {
        TenantContext.runAs(TENANT, () -> {
            routing.resetRotation("FIELD_OPS");
            routing.resetRotation("ONLINE");
        });
        clean();
        TenantContext.clear();
    }

    @Test
    @DisplayName("同一组连续派单：两个人轮流接，而不是永远给同一个")
    void dutyUserRotatesWithinGroup() {
        List<Long> picks = TenantContext.callAs(TENANT, () -> List.of(
                routing.dutyUser("FIELD_OPS"), routing.dutyUser("FIELD_OPS"), routing.dutyUser("FIELD_OPS")));

        assertEquals(2, picks.stream().distinct().count(), "两个人必须都轮到：" + picks);
        assertNotEquals(picks.get(0), picks.get(1), "连续两单不能落到同一个人");
        assertEquals(picks.get(0), picks.get(2), "轮完一圈回到第一个人");
    }

    @Test
    @DisplayName("停用账号不参与；人工分拣组没有对应角色时不硬派")
    void disabledUsersExcludedAndUnsortedStaysManual() {
        jdbc.update("update sys_user set status = 0 where id = ?", USER_B);

        Long only = TenantContext.callAs(TENANT, () -> {
            routing.resetRotation("FIELD_OPS");
            return routing.dutyUser("FIELD_OPS");
        });
        assertEquals(USER_A, only, "停用的账号不能被派单");

        assertNull(TenantContext.callAs(TENANT, () -> routing.dutyUser("CUSTOMER_SERVICE")),
                "人工分拣没有对应角色：保持未指派，等人工接");
    }

    @Test
    @DisplayName("组内没有任何启用账号：返回 null，调用方保持未指派而不是报错")
    void emptyGroupReturnsNull() {
        jdbc.update("update sys_user set status = 0");
        assertNull(TenantContext.callAs(TENANT, () -> routing.dutyUser("FIELD_OPS")));
        assertTrue(true);
    }

    private void insertUser(Long id, String username, int status) {
        jdbc.update("insert into sys_user (id, tenant_id, username, password_hash, real_name, status, create_time, update_time, deleted) "
                + "values (?, ?, ?, '!disabled', ?, ?, NOW(3), NOW(3), 0)",
                id, TENANT, username, username, status);
    }

    private void clean() {
        jdbc.update("delete from sys_user_role where tenant_id = ?", TENANT);
        jdbc.update("delete from sys_user where tenant_id = ?", TENANT);
        jdbc.update("delete from sys_role where tenant_id = ?", TENANT);
    }
}
