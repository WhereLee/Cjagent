package com.wherelee.cabinet.funds;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.funds.app.OrderFundService;
import com.wherelee.cabinet.funds.app.PointAccountService;
import com.wherelee.cabinet.funds.app.RechargeService;
import com.wherelee.cabinet.storage.app.StorageOrderService;
import com.wherelee.cabinet.storage.api.CreateOrderCommand;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.locker.domain.BizCabinet;
import com.wherelee.cabinet.locker.domain.BizCompartment;
import com.wherelee.cabinet.locker.domain.BizSite;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.locker.api.CabinetStatus;
import com.wherelee.cabinet.device.api.CommandAction;
import com.wherelee.cabinet.locker.api.OnlineState;
import com.wherelee.cabinet.locker.api.SizeType;
import com.wherelee.cabinet.locker.api.SlotStatus;
import com.wherelee.cabinet.locker.infra.BizCabinetMapper;
import com.wherelee.cabinet.locker.infra.BizCompartmentMapper;
import com.wherelee.cabinet.locker.infra.BizLockerConsoleMapper;
import com.wherelee.cabinet.locker.infra.BizSiteMapper;
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

@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest(properties = {
        "cabinet.scheduler.poll-enabled=false",
        "cabinet.scheduler.hold-grace-minutes=0",
        "cabinet.door-watch.first-remind-minutes=0",
})
@DisplayName("欠费清偿：不足额不动钱、足额自动放行、先欠先还、重复触发不双扣")
class ArrearsRepayFlowTest {

    private static final long FREEZE_DEFAULT = 215L;

    private static final Long TENANT = 8123L;

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
    private BizLockerConsoleMapper consoleMapper;
    @Autowired
    private PointAccountService points;
    @Autowired
    private RechargeService rechargeService;
    @Autowired
    private OrderFundService funds;
    @Autowired
    private StorageOrderService orderService;

    private JdbcTemplate jdbc;
    private String cabinetNo;
    private Long cabinetId;
    private final Set<Long> customerIds = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(dataSource);
        cabinetNo = "CAB-R-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        TenantContext.runAs(TENANT, () -> {
            BizSite site = new BizSite();
            site.setSiteCode("R-" + cabinetNo);
            site.setName("清偿点位");
            site.setCategory("MALL");
            site.setStatus(1);
            siteMapper.insert(site);

            BizCabinet cabinet = new BizCabinet();
            cabinet.setSiteId(site.getId());
            cabinet.setCabinetNo(cabinetNo);
            cabinet.setName("清偿柜机");
            cabinet.setModelId(90001L);
            cabinet.setCabinetStatus(CabinetStatus.ENABLED);
            cabinet.setOnlineState(OnlineState.ONLINE);
            cabinetMapper.insert(cabinet);
            cabinetId = cabinet.getId();

            for (SizeType size : SizeType.values()) {
                for (int i = 1; i <= 4; i++) {
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
        jdbc.update("delete from biz_site where tenant_id = ? and site_code = ?", TENANT, "R-" + cabinetNo);
        if (hasCustomers) {
            String marks = placeholders();
            jdbc.update("delete from biz_pay_txn where customer_id in (" + marks + ")", customers);
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
        Long id = 997000L + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 20000);
        customerIds.add(id);
        jdbc.update("insert into biz_customer (id, tenant_id, open_id, nickname, status, register_time, create_time, update_time, deleted) "
                        + "values (?, ?, ?, '清偿客户', 1, now(3), now(3), now(3), 0)",
                id, TENANT, "tk-" + id);
        return id;
    }

    private long balance(Long customerId) {
        Long v = jdbc.queryForObject("select points from biz_point_account where customer_id = ?", Long.class, customerId);
        return v == null ? 0L : v;
    }

    private BizStorageOrder order(String orderNo) {
        return TenantContext.callAs(TENANT, () -> orderMapper.selectOne(
                Wrappers.<BizStorageOrder>lambdaQuery().eq(BizStorageOrder::getOrderNo, orderNo)));
    }

    private void fund(Long customerId, long amount) {
        TenantContext.runAs(TENANT, () -> points.recharge(customerId, amount,
                "CHG-" + UUID.randomUUID(), "清偿用例 funding"));
    }

    private String createOrder(Long customerId) {
        return TenantContext.callAs(TENANT, () -> orderService.create(customerId,
                new CreateOrderCommand(UUID.randomUUID().toString(), cabinetNo, "SMALL"))).orderNo();
    }

    private String openAndClose(Long customerId, String orderNo) {
        TenantContext.runAs(TENANT, () -> orderService.openDoor(customerId, orderNo, CommandAction.OPEN));
        TenantContext.runAs(TENANT, () -> orderService.openDoor(customerId, orderNo, CommandAction.CLOSE_VERIFY));
        return orderNo;
    }

    private BizStorageOrder settleIntoArrears(Long customerId, String orderNo) {
        jdbc.update("update biz_storage_order set started_at = date_sub(now(3), interval 100 hour) where order_no = ?", orderNo);
        TenantContext.runAs(TENANT, () -> orderService.pickup(customerId, orderNo));
        BizStorageOrder settled = order(orderNo);
        assertNotNull(settled.getFinishedAt(), "前置数据没铺好：单没结算");
        assertTrue(settled.getArrearsPoints() != null && settled.getArrearsPoints() > 0,
                "前置数据没铺好：结算没产生欠费，arrears=" + settled.getArrearsPoints());
        return settled;
    }

    private BizStorageOrder oneArrearsOrder(Long customerId) {
        fund(customerId, FREEZE_DEFAULT);
        String orderNo = createOrder(customerId);
        return settleIntoArrears(customerId, openAndClose(customerId, orderNo));
    }

    private int repayTxnCount(Long customerId, String orderNo) {
        return jdbc.queryForObject("select count(*) from biz_point_txn where customer_id = ? and biz_no = ?",
                Integer.class, customerId, orderNo + ":repay");
    }

    @Test
    @DisplayName("欠费确实挡单，而拒单文案承诺的【补缴】真的存在：补足后立刻能下单")
    void arrearsBlocksOrderAndRealRechargeClearsIt() {
        Long customerId = newCustomer();
        BizStorageOrder owed = oneArrearsOrder(customerId);

        BizException blocked = assertThrows(BizException.class, () -> TenantContext.runAs(TENANT,
                () -> orderService.create(customerId, new CreateOrderCommand(UUID.randomUUID().toString(), cabinetNo, "SMALL"))));
        assertTrue(blocked.getMessage().contains("未缴清"), blocked.getMessage());
        assertTrue(blocked.getMessage().contains("补缴"), "文案指向的动作必须真的存在：" + blocked.getMessage());

        TenantContext.runAs(TENANT, () -> rechargeService.recharge(customerId, 40L));

        assertTrue(order(owed.getOrderNo()).getArrearsPoints() > 0, "没还完就不得放行");

        TenantContext.runAs(TENANT, () -> rechargeService.recharge(customerId, 300L));
        assertEquals(0L, order(owed.getOrderNo()).getArrearsPoints(), "补足后欠额必须归零");
        assertEquals(1, repayTxnCount(customerId, owed.getOrderNo()), "清偿要留下一条流水");

        fund(customerId, FREEZE_DEFAULT);
        assertNotNull(TenantContext.callAs(TENANT, () -> orderService.create(customerId,
                new CreateOrderCommand(UUID.randomUUID().toString(), cabinetNo, "SMALL"))),
                "还清必须真能下单，否则拦截就是永久拦截");
    }

    @Test
    @DisplayName("不足额一分不扣：宁可留着钱仍被挡，也不能【吃掉 50 点还是不能下单】")
    void partialTopUpNeverEatsTheMoney() {
        Long customerId = newCustomer();
        BizStorageOrder owed = oneArrearsOrder(customerId);
        long owedAmount = owed.getArrearsPoints();

        TenantContext.runAs(TENANT, () -> rechargeService.recharge(customerId, 50L));

        assertEquals(50L, balance(customerId), "不足额清偿不该动钱，余额应等于本次充值额");
        assertEquals(owedAmount, order(owed.getOrderNo()).getArrearsPoints(), "欠额不该被部分抵扣");
        assertEquals(0, repayTxnCount(customerId, owed.getOrderNo()), "没扣钱就不该留下补缴流水");
    }

    @Test
    @DisplayName("欠款存在时开不出第二单 → 欠款天然最多一条（定-5 与定-7）")
    void arrearsCannotStackTwoOrders() {
        Long customerId = newCustomer();
        BizStorageOrder owed = oneArrearsOrder(customerId);
        long each = owed.getArrearsPoints();

        fund(customerId, each * 2);
        assertThrows(BizException.class, () -> TenantContext.runAs(TENANT,
                () -> orderService.create(customerId,
                        new CreateOrderCommand(UUID.randomUUID().toString(), cabinetNo, "SMALL"))));

        assertEquals(1, TenantContext.callAs(TENANT, () -> orderMapper.listOwed(customerId)).size(),
                "一个人名下只能有一条欠款：第二条在下单那一步就被挂了欠款的自己堵死了");
    }

    @Test
    @DisplayName("清偿可重复触发且不双扣；没有欠费时直接返回 0")
    void repayIsIdempotentAcrossTriggers() {
        Long customerId = newCustomer();
        BizStorageOrder owed = oneArrearsOrder(customerId);
        fund(customerId, owed.getArrearsPoints() * 4);

        long firstRun = TenantContext.callAs(TENANT, () -> funds.repayArrears(customerId));
        long balanceAfterFirst = balance(customerId);
        assertEquals(owed.getArrearsPoints(), firstRun);

        assertEquals(0L, TenantContext.callAs(TENANT, () -> funds.repayArrears(customerId)), "已无欠费时不该再扣");
        assertEquals(0L, TenantContext.callAs(TENANT, () -> funds.repayArrears(customerId)));
        assertEquals(balanceAfterFirst, balance(customerId), "重复触发不得再扣一次钱");
        assertEquals(1, repayTxnCount(customerId, owed.getOrderNo()), "同一张单只应留一条补缴流水");
    }

    @Test
    @DisplayName("与后台欠费台账同一口径：清偿后这个客户从台账消失")
    void clearedCustomerLeavesTheArrearsLedger() {
        Long customerId = newCustomer();
        BizStorageOrder owed = oneArrearsOrder(customerId);
        assertTrue(TenantContext.callAs(TENANT, () -> consoleMapper.sumCustomerArrears(customerId)) > 0,
                "台账应先显示他欠着");

        fund(customerId, owed.getArrearsPoints());
        TenantContext.runAs(TENANT, () -> funds.repayArrears(customerId));

        assertEquals(0L, TenantContext.callAs(TENANT, () -> consoleMapper.sumCustomerArrears(customerId)),
                "拦截、台账、清偿必须共用同一条 SQL 口径，否则会出现“后台说没欠、下单却被拒”");
    }
}
