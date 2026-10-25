package com.wherelee.cabinet;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.funds.app.OrderFundService;
import com.wherelee.cabinet.funds.app.PointAccountService;
import com.wherelee.cabinet.storage.app.StorageOrderFacade;
import com.wherelee.cabinet.storage.api.CreateOrderCommand;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.locker.domain.BizCabinet;
import com.wherelee.cabinet.locker.domain.BizCompartment;
import com.wherelee.cabinet.funds.domain.BizDeposit;
import com.wherelee.cabinet.locker.domain.BizSite;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.locker.api.CabinetStatus;
import com.wherelee.cabinet.device.api.CommandAction;
import com.wherelee.cabinet.funds.api.DepositStatus;
import com.wherelee.cabinet.locker.api.OnlineState;
import com.wherelee.cabinet.domain.enums.OrderStatus;
import com.wherelee.cabinet.locker.api.SizeType;
import com.wherelee.cabinet.locker.api.SlotStatus;
import com.wherelee.cabinet.device.infra.SimulatedCabinetChannel;
import com.wherelee.cabinet.locker.infra.BizCabinetMapper;
import com.wherelee.cabinet.locker.infra.BizCompartmentMapper;
import com.wherelee.cabinet.funds.infra.BizDepositMapper;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest
class PointLedgerTest {

    private static final Long TENANT = 8101L;

    @Autowired
    private PointAccountService points;
    @Autowired
    private OrderFundService funds;
    @Autowired
    private StorageOrderFacade facade;
    @Autowired
    private SimulatedCabinetChannel simulator;
    @Autowired
    private BizSiteMapper siteMapper;
    @Autowired
    private BizCabinetMapper cabinetMapper;
    @Autowired
    private BizCompartmentMapper slotMapper;
    @Autowired
    private BizStorageOrderMapper orderMapper;
    @Autowired
    private BizDepositMapper depositMapper;
    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private Long cabinetId;
    private String cabinetNo;
    private final List<Long> customerIds = new java.util.ArrayList<>();

    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(dataSource);
        simulator.reset();
        cabinetNo = "CAB-P-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        TenantContext.runAs(TENANT, () -> {
            BizSite site = new BizSite();
            site.setSiteCode("S-" + cabinetNo);
            site.setName("账本点位");
            site.setCategory("MALL");
            site.setStatus(1);
            siteMapper.insert(site);

            BizCabinet cabinet = new BizCabinet();
            cabinet.setSiteId(site.getId());
            cabinet.setCabinetNo(cabinetNo);
            cabinet.setName("账本柜机");
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
        List<Long> orders = jdbc.query("select id from biz_storage_order where cabinet_id = ?",
                (rs, i) -> rs.getLong(1), cabinetId);
        if (!orders.isEmpty()) {
            String in = orders.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("0");
            jdbc.update("delete from biz_point_txn where ref_id in (" + in + ")");
            jdbc.update("delete from biz_deposit where order_id in (" + in + ")");
        }
        jdbc.update("delete from biz_device_command where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_device_report where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_storage_order where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_compartment where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_cabinet where id = ?", cabinetId);
        jdbc.update("delete from biz_site where tenant_id = ? and site_code = ?", TENANT, "S-" + cabinetNo);
        for (Long customerId : customerIds) {
            jdbc.update("delete from biz_point_txn where customer_id = ?", customerId);
            jdbc.update("delete from biz_point_account where customer_id = ?", customerId);
        }
        customerIds.clear();
        simulator.reset();
        TenantContext.clear();
    }

    private Long newCustomer() {
        Long id = 950000L + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 90000);
        customerIds.add(id);
        return id;
    }

    private BizStorageOrder order(String orderNo) {
        return TenantContext.callAs(TENANT, () -> orderMapper.selectOne(
                Wrappers.<BizStorageOrder>lambdaQuery().eq(BizStorageOrder::getOrderNo, orderNo)));
    }

    private String createOrder(Long customerId, SizeType size) {
        return TenantContext.callAs(TENANT, () -> facade.create(customerId,
                new CreateOrderCommand(UUID.randomUUID().toString(), cabinetNo, size.name()))).orderNo();
    }

    @Test
    @DisplayName("不变量 3：任意操作序列后，两栏余额都等于对应流水之和")
    void ledgerStaysBalanced() {
        Long customerId = newCustomer();

        TenantContext.runAs(TENANT, () -> {
            points.recharge(customerId, 1000, "CHG-" + UUID.randomUUID(), "充值");
            points.freeze(customerId, 300, "TEST", null, "biz-" + UUID.randomUUID(), "冻结");
            points.unfreeze(customerId, 120, "TEST", null, "biz-" + UUID.randomUUID(), "部分解冻");
            points.consume(customerId, 80, "TEST", null, "biz-" + UUID.randomUUID(), "消耗");
            points.post(customerId, com.wherelee.cabinet.funds.api.PointTxnType.ADJUST, -5,
                    "TEST", null, "biz-" + UUID.randomUUID(), "人工冲正");
        });

        String diff = TenantContext.callAs(TENANT, () -> points.verifyLedger(customerId));
        assertEquals("", diff, "账不平：流水求和与余额对不上就是这条不变量被破坏");

        var account = TenantContext.callAs(TENANT, () -> points.accountOf(customerId));
        assertNotNull(account);
        assertEquals(735L, account.getPoints());
        assertEquals(180L, account.getFrozenPoints());
    }

    @Test
    @DisplayName("并发扣减不超扣：20 线程抢 100 点，只可能成功 3 次")
    void concurrentConsumeNeverOverspends() throws Exception {
        Long customerId = newCustomer();
        final long initial = 100L;
        final long each = 30L;
        TenantContext.runAs(TENANT, () -> points.recharge(customerId, initial, "CHG-" + UUID.randomUUID(), "充值"));

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    TenantContext.runAs(TENANT, () -> {
                        try {
                            points.consume(customerId, each, "TEST", null, "race-" + UUID.randomUUID(), "并发扣");
                            succeeded.incrementAndGet();
                        } catch (com.wherelee.cabinet.common.exception.BizException e) {
                            if (e.getResultCode() == com.wherelee.cabinet.common.api.ResultCode.POINT_INSUFFICIENT) {
                                insufficient.incrementAndGet();
                            }
                        }
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发扣减未收敛，可能卡在行锁上");
        pool.shutdownNow();

        assertEquals(3, succeeded.get(), "成功次数超过余额允许的上限＝超扣");
        assertEquals(threads, succeeded.get() + insufficient.get(), "每个请求都必须有明确结果");

        var account = TenantContext.callAs(TENANT, () -> points.accountOf(customerId));
        assertEquals(10L, account.getPoints(), "100 - 3*30 应该剩 10");
        assertTrue(account.getPoints() >= 0, "余额为负＝CHECK 与条件更新双双失效");
        assertEquals("", TenantContext.callAs(TENANT, () -> points.verifyLedger(customerId)));
    }

    @Test
    @DisplayName("充值幂等：同一通道流水号只入账一次")
    void rechargeIsIdempotent() {
        Long customerId = newCustomer();
        String tradeNo = "TRADE-" + UUID.randomUUID();

        TenantContext.runAs(TENANT, () -> {
            points.recharge(customerId, 500, tradeNo, "首次");
            points.recharge(customerId, 500, tradeNo, "重复回调");
        });

        var account = TenantContext.callAs(TENANT, () -> points.accountOf(customerId));
        assertEquals(500L, account.getPoints(), "重复回调不得变成两次入账（支付回调一定会重发）");
        assertEquals("", TenantContext.callAs(TENANT, () -> points.verifyLedger(customerId)));
    }

    @Test
    @DisplayName("下单冻结 → 取件结算：押金退回、欠费为零、账仍平")
    void fullFlowFreezeThenSettle() {
        Long customerId = newCustomer();
        TenantContext.runAs(TENANT, () -> points.recharge(customerId, 2000, "CHG-" + UUID.randomUUID(), "充值"));

        String orderNo = createOrder(customerId, SizeType.MEDIUM);
        BizStorageOrder created = order(orderNo);
        assertEquals(0L, created.getFrozenPoints(), "C：下单不再预冻任何费用");
        assertEquals(1900L, accountPoints(customerId), "可用栏 = 充值 - 划账户押金（不再有冻结）");
        assertEquals(0L, created.getDepositPoints(), "新单不再有每单押金（历史单仍按自己身上的金额退）");
        assertNotNull(created.getPricingSnapshot());
        assertTrue(created.getPricingSnapshot().contains("unitPointsPerHour"), "定价快照必须落库");

        TenantContext.runAs(TENANT, () -> {
            facade.openDoor(customerId, orderNo, CommandAction.OPEN);
            facade.openDoor(customerId, orderNo, CommandAction.CLOSE_VERIFY);
        });
        assertEquals(OrderStatus.ACTIVE.name(), order(orderNo).getStatus().name(), "关门校验后应开始计费");
        assertEquals(SlotStatus.OCCUPIED, slotStatus(orderNo), "件已入柜，格口必须是 OCCUPIED 而不是 RESERVED");

        backdateStart(orderNo, 120);

        var view = TenantContext.callAs(TENANT, () -> facade.pickup(customerId, orderNo));
        assertEquals(OrderStatus.CLOSED.name(), view.status());

        BizStorageOrder settled = order(orderNo);
        assertEquals(0L, settled.getFrozenPoints(), "结算后不得留有冻结");
        assertEquals(0L, settled.getArrearsPoints(), "余额充足却记欠费＝结算算错");
        assertEquals(50L, settled.getSettledPoints(), "结算额必须等于快照单价 × 计费小时");

        var account = TenantContext.callAs(TENANT, () -> points.accountOf(customerId));
        assertTrue(account.getFrozenPoints() == 0L, "冻结栏必须清零，押金要回到可用栏");
        assertEquals(SlotStatus.FREE, slotStatus(orderNo), "取件后格口必须释放");

        assertEquals(0L, TenantContext.callAs(TENANT, () -> points.accountOf(customerId)).getDepositPoints(),
                "账户不应再有押金：预收已经替它完成了担保");
        assertEquals("", TenantContext.callAs(TENANT, () -> points.verifyLedger(customerId)));
    }

    @Test
    @DisplayName("欠费仍可取件：实际用时远超预收、扣不到部分记未支付，但不锁件")
    void pickupWorksEvenWhenBroke() {
        Long customerId = newCustomer();
        TenantContext.runAs(TENANT, () -> points.recharge(customerId, 200, "CHG-" + UUID.randomUUID(), "只够预收+一点余额"));

        String orderNo = createOrder(customerId, SizeType.MEDIUM);
        TenantContext.runAs(TENANT, () -> {
            facade.openDoor(customerId, orderNo, CommandAction.OPEN);
            facade.openDoor(customerId, orderNo, CommandAction.CLOSE_VERIFY);
        });
        backdateStart(orderNo, 1000);

        var view = TenantContext.callAs(TENANT, () -> facade.pickup(customerId, orderNo));

        assertEquals(OrderStatus.CLOSED.name(), view.status(), "欠费不能阻塞取件——东西是用户的");
        assertEquals(SlotStatus.FREE, slotStatus(orderNo), "件必须能拿出来");
        BizStorageOrder after = order(orderNo);
        assertEquals(200L, after.getSettledPoints(), "能扣的先扣完（预收已到账，不算扣不到的那部分）");
        assertEquals(50L, after.getArrearsPoints(), "扣不到的部分必须记成未支付，不能抹掉");
        assertEquals(250L, after.getSettledPoints() + after.getArrearsPoints(),
                "应缴总额 = 封顶后的 10 小时价；超过单日封顶的那 7 小时不得再收（规划 §5.10）");

        assertEquals(0L, TenantContext.callAs(TENANT, () -> points.accountOf(customerId)).getDepositPoints(),
                "欠费不得自动占用任何担保额度");
        assertEquals("", TenantContext.callAs(TENANT, () -> points.verifyLedger(customerId)));
    }

    @Test
    @DisplayName("结算读定价快照而不是当前配置：改价不得追溯影响已下的单")
    void settleUsesSnapshotNotCurrentConfig() {
        Long customerId = newCustomer();
        TenantContext.runAs(TENANT, () -> points.recharge(customerId, 5000, "CHG-" + UUID.randomUUID(), "充值"));
        String orderNo = createOrder(customerId, SizeType.SMALL);
        long orderId = order(orderNo).getId();

        jdbc.update("update biz_storage_order set pricing_snapshot = "
                + "json_set(pricing_snapshot, '$.unitPointsPerHour', 1234) where id = ?", orderId);

        TenantContext.runAs(TENANT, () -> {
            facade.openDoor(customerId, orderNo, CommandAction.OPEN);
            facade.openDoor(customerId, orderNo, CommandAction.CLOSE_VERIFY);
        });
        backdateStart(orderNo, 120);
        TenantContext.runAs(TENANT, () -> facade.pickup(customerId, orderNo));

        BizStorageOrder settled = order(orderNo);
        assertEquals(1234L * 2, settled.getSettledPoints(),
                "结算必须按快照单价算；读了当前配置就说明历史单被改价追溯影响");
        assertEquals("", TenantContext.callAs(TENANT, () -> points.verifyLedger(customerId)));
    }

    @Test
    @DisplayName("取消退全部冻结：可用栏回到划押后的水平，押金不动")
    void cancelRefundsEverything() {
        Long customerId = newCustomer();
        TenantContext.runAs(TENANT, () -> points.recharge(customerId, 1000, "CHG-" + UUID.randomUUID(), "充值"));
        String orderNo = createOrder(customerId, SizeType.LARGE);
        assertEquals(0L, accountFrozen(customerId), "C：下单后不应有任何冻结");

        TenantContext.runAs(TENANT, () -> facade.cancel(customerId, orderNo));

        var account = TenantContext.callAs(TENANT, () -> points.accountOf(customerId));
        assertEquals(1000L, account.getPoints(), "可用栏不变（本就没冻东西）；押金仍押在账户里（它不跟单走）");
        assertEquals(0L, account.getFrozenPoints());
        assertEquals("", TenantContext.callAs(TENANT, () -> points.verifyLedger(customerId)));
        assertEquals(0L, account.getDepositPoints(), "押金整套已作废（19A 预收取代），不得再有押金额度");
    }

    private SlotStatus slotStatus(String orderNo) {
        Long slotId = order(orderNo).getSlotId();
        return TenantContext.callAs(TENANT, () -> slotMapper.selectById(slotId)).getStatus();
    }

    private void backdateStart(String orderNo, long minutesAgo) {
        jdbc.update("update biz_storage_order set started_at = date_sub(now(3), interval ? minute) where order_no = ?",
                minutesAgo, orderNo);
    }

    private long accountPoints(Long customerId) {
        return TenantContext.callAs(TENANT, () -> points.accountOf(customerId)).getPoints();
    }

    private long accountFrozen(Long customerId) {
        return TenantContext.callAs(TENANT, () -> points.accountOf(customerId)).getFrozenPoints();
    }
}
