package com.wherelee.cabinet;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.application.point.PointAccountService;
import com.wherelee.cabinet.application.storage.StorageOrderService;
import com.wherelee.cabinet.application.storage.dto.CreateOrderCommand;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.entity.BizCabinet;
import com.wherelee.cabinet.domain.entity.BizCompartment;
import com.wherelee.cabinet.domain.entity.BizSite;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.CabinetStatus;
import com.wherelee.cabinet.domain.enums.CommandAction;
import com.wherelee.cabinet.domain.enums.OnlineState;
import com.wherelee.cabinet.domain.enums.SizeType;
import com.wherelee.cabinet.domain.enums.SlotStatus;
import com.wherelee.cabinet.infrastructure.mapper.BizCabinetMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizCompartmentMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizSiteMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户侧三条新规则（2026-10-10 定稿 定-5、定-6、定-8）。
 *
 * <p>三条都是"把系统往更保守的方向收"：一人一单、不给升尺寸、码错多了就锁。
 * 这类规则最容易被后来的优化悄悄放宽（"体验不好，帮他升一格吧"），所以每条都留断言钉住。
 *
 * <p>造数走真实下单链路，不直接插订单行——一人一单的判定依赖 {@code active_flag}
 * 这个结构化标记，手工插的行会把"占位是否真的生效"这件事测漏。
 */
@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest(properties = {
        "cabinet.scheduler.poll-enabled=false",
        "cabinet.scheduler.hold-grace-minutes=0",
        "cabinet.pricing.tolerance-minutes=0",
})
@DisplayName("用户侧：一人只能占一格、没位就是没位、取件码错 5 次锁定")
class SingleSlotAndVoucherTest {

    /** 当前默认价：押金 200 + 小格 1 小时 15 点。账户级押金改造（18-2）后这个数会变。 */
    private static final long FREEZE = 215L;

    private static final Long TENANT = 8125L;

    @Autowired
    private DataSource dataSource;
    @Autowired
    private BizSiteMapper siteMapper;
    @Autowired
    private BizCabinetMapper cabinetMapper;
    @Autowired
    private BizCompartmentMapper slotMapper;
    @Autowired
    private BizStorageOrderMapper orderMapper;
    @Autowired
    private PointAccountService points;
    @Autowired
    private StorageOrderService orderService;

    private JdbcTemplate jdbc;
    private String cabinetNo;
    private Long cabinetId;
    private final Set<Long> customerIds = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(dataSource);
        cabinetNo = "CAB-S-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        TenantContext.runAs(TENANT, () -> {
            BizSite site = new BizSite();
            site.setSiteCode("S-" + cabinetNo);
            site.setName("单格点位");
            site.setCategory("MALL");
            site.setStatus(1);
            siteMapper.insert(site);

            BizCabinet cabinet = new BizCabinet();
            cabinet.setSiteId(site.getId());
            cabinet.setCabinetNo(cabinetNo);
            cabinet.setName("单格柜机");
            cabinet.setModelId(90001L);
            cabinet.setCabinetStatus(CabinetStatus.ENABLED);
            cabinet.setOnlineState(OnlineState.ONLINE);
            cabinetMapper.insert(cabinet);
            cabinetId = cabinet.getId();

            // 每档 3 个：够把小格占满，用来验"没位就是没位，不会去抢中格"
            for (SizeType size : SizeType.values()) {
                for (int i = 1; i <= 3; i++) {
                    BizCompartment slot = new BizCompartment();
                    slot.setCabinetId(cabinetId);
                    slot.setSlotNo(size.name().charAt(0) + String.format("%02d", i));
                    slot.setSizeType(size);
                    slot.setStatus(SlotStatus.FREE);
                    slotMapper.insert(slot);
                }
            }
        });
    }

    @AfterEach
    void cleanup() {
        boolean hasCustomers = !customerIds.isEmpty();
        Object[] customers = customerIds.toArray();
        jdbc.update("delete from biz_delay_task where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_fault_event where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_item_evidence where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_device_report where order_id in (select id from biz_storage_order where cabinet_id = ?)", cabinetId);
        jdbc.update("delete from biz_device_command where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_deposit where order_id in (select id from biz_storage_order where cabinet_id = ?)", cabinetId);
        jdbc.update("delete from biz_storage_order where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_compartment where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_cabinet where id = ?", cabinetId);
        jdbc.update("delete from biz_site where tenant_id = ? and site_code = ?", TENANT, "S-" + cabinetNo);
        if (hasCustomers) {
            String marks = placeholders();
            jdbc.update("delete from biz_point_txn where customer_id in (" + marks + ")", customers);
            jdbc.update("delete from biz_point_account where customer_id in (" + marks + ")", customers);
            jdbc.update("delete from biz_customer where id in (" + marks + ")", customers);
        }
        customerIds.clear();
        TenantContext.clear();
    }

    private String placeholders() {
        return customerIds.stream().map(id -> "?").reduce((a, b) -> a + "," + b).orElse("?");
    }

    private Long newCustomer() {
        Long id = 998000L + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 15000);
        customerIds.add(id);
        jdbc.update("insert into biz_customer (id, tenant_id, open_id, nickname, status, register_time, create_time, update_time, deleted) "
                        + "values (?, ?, ?, '单格客户', 1, now(3), now(3), now(3), 0)",
                id, TENANT, "tk-" + id);
        Long finalId = id;
        TenantContext.runAs(TENANT, () -> points.recharge(finalId, FREEZE * 4,
                "CHG-" + UUID.randomUUID(), "单格用例 funding"));
        return id;
    }

    private String orderNo(Long customerId, SizeType size) {
        return TenantContext.callAs(TENANT, () -> orderService.create(customerId,
                new CreateOrderCommand(UUID.randomUUID().toString(), cabinetNo, size.name(), 60))).orderNo();
    }

    private BizStorageOrder order(String orderNo) {
        return TenantContext.callAs(TENANT, () -> orderMapper.selectOne(
                Wrappers.<BizStorageOrder>lambdaQuery().eq(BizStorageOrder::getOrderNo, orderNo)));
    }

    private void door(String orderNo, Long customerId, CommandAction action) {
        TenantContext.runAs(TENANT, () -> orderService.openDoor(customerId, orderNo, action));
    }

    private long occupiedBy(SizeType size) {
        // 数“有没有绑着活动单”而不是数 status 字面量：枚举入库形式属于框架细节，
        // 而“这一格里已经站了一张单”才是本用例要断的业务事实
        Long count = jdbc.queryForObject("select count(*) from biz_compartment "
                        + "where cabinet_id = ? and size_type = ? and current_order_id is not null and deleted = 0",
                Long.class, cabinetId, size.name());
        return count == null ? 0L : count;
    }

    @Test
    @DisplayName("手上有一单没结束就不许再开第二格；结束后可以再开")
    void oneActiveOrderPerCustomer() {
        Long customerId = newCustomer();
        String first = orderNo(customerId, SizeType.SMALL);

        BizException second = assertThrows(BizException.class,
                () -> orderNo(customerId, SizeType.SMALL));
        assertTrue(second.getMessage().contains("未结束"), second.getMessage());

        // 结束掉第一张之后，同一个账户应该能正常开新格——拦截不是封号
        door(first, customerId, CommandAction.OPEN);
        door(first, customerId, CommandAction.CLOSE_VERIFY);
        TenantContext.runAs(TENANT, () -> orderService.pickup(customerId, first));

        String next = orderNo(customerId, SizeType.SMALL);
        assertNotNull(next);
        assertEquals(1L, TenantContext.callAs(TENANT, () -> orderMapper.selectCount(
                Wrappers.<BizStorageOrder>lambdaQuery()
                        .eq(BizStorageOrder::getCustomerId, customerId)
                        .eq(BizStorageOrder::getActiveFlag, 1))),
                "同一时刻仍只能有一张活动单");
    }

    @Test
    @DisplayName("小格占满就是【没位】，不会悄悄给用户一个更贵的大格")
    void noFallbackToBiggerSize() {
        // 三个小格被三个不同客户占满（一人一单，所以必须三个客户）
        orderNo(newCustomer(), SizeType.SMALL);
        orderNo(newCustomer(), SizeType.SMALL);
        orderNo(newCustomer(), SizeType.SMALL);
        assertEquals(3L, occupiedBy(SizeType.SMALL), "前置：小格应全部占满");

        Long fourth = newCustomer();
        BizException noSlot = assertThrows(BizException.class, () -> orderNo(fourth, SizeType.SMALL));
        assertTrue(noSlot.getMessage() != null && !noSlot.getMessage().isBlank(), "要说清为什么下不了单");

        // 关键断言：中格与大格一个都没被吃掉。以前"往上找第一个可用"会在这里偷偷给用户一个中格
        assertEquals(0L, occupiedBy(SizeType.MEDIUM), "不允许向上升级到中格");
        assertEquals(0L, occupiedBy(SizeType.LARGE), "不允许向上升级到大格");
    }

    @Test
    @DisplayName("取件码连续错 5 次锁定该单开柜；中途输对一次就重新计数")
    void voucherLocksAfterFiveWrongAttemptsAndResetsOnSuccess() {
        Long customerId = newCustomer();
        String orderNo = orderNo(customerId, SizeType.SMALL);
        door(orderNo, customerId, CommandAction.OPEN);
        door(orderNo, customerId, CommandAction.CLOSE_VERIFY);
        String realCode = order(orderNo).getVoucherCode();
        assertNotNull(realCode);

        for (int n = 1; n <= 4; n++) {
            String bad = "V00000" + n;
            BizException wrong = assertThrows(BizException.class, () -> TenantContext.runAs(TENANT,
                    () -> orderService.pickup(customerId, orderNo, bad)));
            assertTrue(wrong.getMessage().contains("还剩"), "要告诉用户还剩几次：" + wrong.getMessage());
        }
        assertEquals(4, order(orderNo).getVoucherWrongCount().intValue());

        // 第 5 次输对：计数归零，并且能正常结束——上限是“连续”错五次，不是一共错五次
        TenantContext.runAs(TENANT, () -> orderService.pickup(customerId, orderNo, realCode));
        BizStorageOrder settled = order(orderNo);
        assertNotNull(settled.getFinishedAt(), "正确码应当放行取件");
        assertEquals(0, settled.getVoucherWrongCount().intValue(), "输对必须清零，否则攒够五次就被锁");
    }

    @Test
    @DisplayName("锁定后既不能取件也不能临时开柜，但后台强制开柜仍可用（备用路径）")
    void lockedOrderBlocksDoorButNotStaffPath() {
        Long customerId = newCustomer();
        String orderNo = orderNo(customerId, SizeType.SMALL);
        door(orderNo, customerId, CommandAction.OPEN);
        door(orderNo, customerId, CommandAction.CLOSE_VERIFY);

        for (int i = 0; i < 5; i++) {
            assertThrows(BizException.class, () -> TenantContext.runAs(TENANT,
                    () -> orderService.pickup(customerId, orderNo, "V999999")));
        }
        assertNotNull(order(orderNo).getVoucherLockedAt(), "第 5 次错应写下锁定时刻");

        // 锁的是“凭这张单开柜”，不是“把这张单结掉”：
        // 柜内空、门已关时他本人登录态点结束必须能结，否则被锁住的反而是物主
        BizException lockedTemp = assertThrows(BizException.class,
                () -> door(orderNo, customerId, CommandAction.OPEN_TEMP));
        assertTrue(lockedTemp.getMessage().contains("锁定"), lockedTemp.getMessage());
        assertEquals(1L, occupiedBy(SizeType.SMALL), "被锁期间格口仍属于这张单，不能被回收再卖");

        TenantContext.runAs(TENANT, () -> orderService.pickup(customerId, orderNo));
        assertNotNull(order(orderNo).getFinishedAt(), "锁定不影响登录态结束（结掉才能把件拿走或走客服）");
        assertEquals(0L, occupiedBy(SizeType.SMALL), "结束后格口释放");
    }
}
