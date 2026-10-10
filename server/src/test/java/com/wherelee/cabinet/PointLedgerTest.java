package com.wherelee.cabinet;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.application.point.OrderFundService;
import com.wherelee.cabinet.application.point.PointAccountService;
import com.wherelee.cabinet.application.storage.StorageOrderFacade;
import com.wherelee.cabinet.application.storage.dto.CreateOrderCommand;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.domain.entity.BizCabinet;
import com.wherelee.cabinet.domain.entity.BizCompartment;
import com.wherelee.cabinet.domain.entity.BizDeposit;
import com.wherelee.cabinet.domain.entity.BizSite;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.CabinetStatus;
import com.wherelee.cabinet.domain.enums.CommandAction;
import com.wherelee.cabinet.domain.enums.DepositStatus;
import com.wherelee.cabinet.domain.enums.OnlineState;
import com.wherelee.cabinet.domain.enums.OrderStatus;
import com.wherelee.cabinet.domain.enums.SizeType;
import com.wherelee.cabinet.domain.enums.SlotStatus;
import com.wherelee.cabinet.infrastructure.device.SimulatedCabinetChannel;
import com.wherelee.cabinet.infrastructure.mapper.BizCabinetMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizCompartmentMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizDepositMapper;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 点数账本与资金流转（第 12 刀验收）。
 *
 * <p>这一刀把从第 8 刀起就写在规划里、一直没有断言的<b>不变量 3</b> 变成真检查：
 * {@code points == Σ(影响可用栏的流水)} 且 {@code frozen == Σ(影响冻结栏的流水)}。
 * 只要有一条路径"改了余额没记流水"或"记了流水没改余额"，这里的每个用例都会红。
 */
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
                new CreateOrderCommand(UUID.randomUUID().toString(), cabinetNo, size.name(), 120))).orderNo();
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
            points.post(customerId, com.wherelee.cabinet.domain.enums.PointTxnType.ADJUST, -5,
                    "TEST", null, "biz-" + UUID.randomUUID(), "人工冲正");
        });

        String diff = TenantContext.callAs(TENANT, () -> points.verifyLedger(customerId));
        assertEquals("", diff, "账不平：流水求和与余额对不上就是这条不变量被破坏");

        var account = TenantContext.callAs(TENANT, () -> points.accountOf(customerId));
        assertNotNull(account);
        // 1000 - 300 + 120 - 80 - 5 = 735；冻结 300 - 120 = 180
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

        // 100 点、每次 30：最多成功 3 次，且绝不能出现"多成功一次"
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
        // 账户级押金（定-1）：下单只冻预估（120 分 - 10 免费 = 110 → 2 小时 × 25 = 50），
        // 200 点押金从可用搬到押金栅，不再是“这张单的押金”
        assertEquals(50L, created.getFrozenPoints(), "下单只应冻结预估费用，不包含账户押金");
        assertEquals(1750L, accountPoints(customerId), "可用栏 = 充值 - 划押金 - 冻结");
        assertEquals(0L, created.getDepositPoints(), "新单不再有每单押金（历史单仍按自己身上的金额退）");
        assertNotNull(created.getPricingSnapshot());
        assertTrue(created.getPricingSnapshot().contains("unitPointsPerHour"), "定价快照必须落库");

        TenantContext.runAs(TENANT, () -> {
            facade.openDoor(customerId, orderNo, CommandAction.OPEN);
            facade.openDoor(customerId, orderNo, CommandAction.CLOSE_VERIFY);
        });
        assertEquals(OrderStatus.ACTIVE.name(), order(orderNo).getStatus().name(), "关门校验后应开始计费");
        assertEquals(SlotStatus.OCCUPIED, slotStatus(orderNo), "件已入柜，格口必须是 OCCUPIED 而不是 RESERVED");

        // 计时不会自己流逝：不回填 started_at，结算永远落在免费窗口里（need=0），这个用例就什么也没测到
        backdateStart(orderNo, 120);

        var view = TenantContext.callAs(TENANT, () -> facade.pickup(customerId, orderNo));
        assertEquals(OrderStatus.CLOSED.name(), view.status());

        BizStorageOrder settled = order(orderNo);
        assertEquals(0L, settled.getFrozenPoints(), "结算后不得留有冻结");
        assertEquals(0L, settled.getArrearsPoints(), "余额充足却记欠费＝结算算错");
        // 120 分钟 - 10 免费 = 110 → 向上取整 2 小时 × 25 = 50
        assertEquals(50L, settled.getSettledPoints(), "结算额必须等于快照单价 × 计费小时");

        var account = TenantContext.callAs(TENANT, () -> points.accountOf(customerId));
        assertTrue(account.getFrozenPoints() == 0L, "冻结栏必须清零，押金要回到可用栏");
        assertEquals(SlotStatus.FREE, slotStatus(orderNo), "取件后格口必须释放");

        // 结算不碰押金：它长在账户上，只在用户主动“退押金”时才动（定-1/S-03）
        assertEquals(200L, TenantContext.callAs(TENANT, () -> points.accountOf(customerId)).getDepositPoints(),
                "取件结算不得动账户押金");
        assertEquals("", TenantContext.callAs(TENANT, () -> points.verifyLedger(customerId)));
    }

    @Test
    @DisplayName("欠费仍可取件：实际用时远超预估、扣不到部分记欠费，但不锁件")
    void pickupWorksEvenWhenBroke() {
        Long customerId = newCustomer();
        // 只给够“押金 + 预估”的点数；真实用时远超预估时就会扣不到，差额应进欠费而不是锁件
        TenantContext.runAs(TENANT, () -> points.recharge(customerId, 250, "CHG-" + UUID.randomUUID(), "刚好够冻结"));

        String orderNo = createOrder(customerId, SizeType.MEDIUM);
        TenantContext.runAs(TENANT, () -> {
            facade.openDoor(customerId, orderNo, CommandAction.OPEN);
            facade.openDoor(customerId, orderNo, CommandAction.CLOSE_VERIFY);
        });
        // 用时长制造欠费（而不是改余额造现场）：手工把 points 清 0 会直接破坏不变量 3，
        // 那样连“账还是不是平的”都没法验
        backdateStart(orderNo, 1000);

        var view = TenantContext.callAs(TENANT, () -> facade.pickup(customerId, orderNo));

        assertEquals(OrderStatus.CLOSED.name(), view.status(), "欠费不能阻塞取件——东西是用户的");
        assertEquals(SlotStatus.FREE, slotStatus(orderNo), "件必须能拿出来");
        BizStorageOrder after = order(orderNo);
        // 可用只有 50（剩 250 已被划押占用，不参与消费）→ 欠 250
        assertEquals(50L, after.getSettledPoints(), "能扣的先扣完");
        assertEquals(250L, after.getArrearsPoints(), "扣不到的部分必须记成欠费，不能抹掉");
        assertEquals(300L, after.getSettledPoints() + after.getArrearsPoints(),
                "应缴总额 = 封顶后的 12 小时价；超过一天的那 5 小时不得再收（规划 §5.10）");

        // 押金不拿来抵欠：它只在用户主动退押金时才抵（定-1）——所以此处必须仍是 200
        assertEquals(200L, TenantContext.callAs(TENANT, () -> points.accountOf(customerId)).getDepositPoints(),
                "欠费不得自动占用账户押金");
        // 欠费不产生流水（它只是“没收到的钱”），所以账依然必须是平的
        assertEquals("", TenantContext.callAs(TENANT, () -> points.verifyLedger(customerId)));
    }

    @Test
    @DisplayName("结算读定价快照而不是当前配置：改价不得追溯影响已下的单")
    void settleUsesSnapshotNotCurrentConfig() {
        Long customerId = newCustomer();
        TenantContext.runAs(TENANT, () -> points.recharge(customerId, 5000, "CHG-" + UUID.randomUUID(), "充值"));
        String orderNo = createOrder(customerId, SizeType.SMALL);
        long orderId = order(orderNo).getId();

        // 把快照里的单价改成一个配置里根本不存在的值，等价于"这单是在历史价格下创建的"
        jdbc.update("update biz_storage_order set pricing_snapshot = "
                + "json_set(pricing_snapshot, '$.unitPointsPerHour', 1234) where id = ?", orderId);

        TenantContext.runAs(TENANT, () -> {
            facade.openDoor(customerId, orderNo, CommandAction.OPEN);
            facade.openDoor(customerId, orderNo, CommandAction.CLOSE_VERIFY);
        });
        // 不回填时间就落在免费窗口（need=0），“读不读配置”根本测不出来
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
        // 预估（120 分 - 10 免费 = 110 → 2 小时 × LARGE 40 = 80）；200 点押金在另一栅，不算冻结
        assertEquals(80L, accountFrozen(customerId), "下单后只应冻住预估费用");

        TenantContext.runAs(TENANT, () -> facade.cancel(customerId, orderNo));

        var account = TenantContext.callAs(TENANT, () -> points.accountOf(customerId));
        assertEquals(800L, account.getPoints(), "取消必须退回预估冻结；押金仍押在账户里（它不跟单走）");
        assertEquals(0L, account.getFrozenPoints());
        assertEquals("", TenantContext.callAs(TENANT, () -> points.verifyLedger(customerId)));
        assertEquals(200L, account.getDepositPoints(), "取消不得把账户押金退成可花");
    }

    private SlotStatus slotStatus(String orderNo) {
        Long slotId = order(orderNo).getSlotId();
        return TenantContext.callAs(TENANT, () -> slotMapper.selectById(slotId)).getStatus();
    }

    /** 回填计费开始时间：把“实际用时”变成可控量（而不是真等 2 小时）。 */
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
