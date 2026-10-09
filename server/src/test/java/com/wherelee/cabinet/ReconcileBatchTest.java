package com.wherelee.cabinet;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.application.point.PointAccountService;
import com.wherelee.cabinet.application.reconcile.ReconcileReport;
import com.wherelee.cabinet.application.reconcile.ReconcileService;
import com.wherelee.cabinet.application.storage.StorageOrderService;
import com.wherelee.cabinet.application.storage.dto.CreateOrderCommand;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.domain.entity.BizCabinet;
import com.wherelee.cabinet.domain.entity.BizCompartment;
import com.wherelee.cabinet.domain.entity.BizDeposit;
import com.wherelee.cabinet.domain.entity.BizSite;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.CabinetStatus;
import com.wherelee.cabinet.domain.enums.OnlineState;
import com.wherelee.cabinet.domain.enums.SizeType;
import com.wherelee.cabinet.domain.enums.SlotStatus;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对账批次（第 13 刀）。
 *
 * <p>这个类要证的不是"能跑"，而是四件容易做错的事：
 * ① <b>差异判据精确</b>——活动单的 HELD 押金是正常态，不能报成悬挂（报了就等于把真问题埋进假阳性里）；
 * ② <b>只报告不改钱</b>——跑完账本必须还是错的（现场留着），修钱只能人工 ADJUST；
 * ③ <b>分批不漏不重</b>——batchSize 开到 2 也要扫完全部账户；
 * ④ <b>租户边界</b>——别的租户的不平不能污染本租户的结论（这里所有 SQL 都自己写 tenant 条件，
 *    正因为 join 上的拦截器行为不可依赖，所以必须有用例钉住）。
 *
 * <p>关掉后台轮询与调度登记：本类只调 {@code reconcile()}，不希望有别的线程在改数据。
 */
@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest(properties = {
        "cabinet.scheduler.poll-enabled=false",
        "cabinet.scheduler.reconcile-batch-size=2",
        "cabinet.scheduler.hold-grace-minutes=60"
})
class ReconcileBatchTest {

    private static final Long TENANT = 8112L;
    private static final Long OTHER_TENANT = 8113L;

    @Autowired
    private ReconcileService reconcile;
    @Autowired
    private PointAccountService points;
    @Autowired
    private StorageOrderService orderService;
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
    private final Set<Long> customerIds = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(dataSource);
        cabinetNo = "CAB-R-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        TenantContext.runAs(TENANT, () -> {
            BizSite site = new BizSite();
            site.setSiteCode("R-" + cabinetNo);
            site.setName("对账点位");
            site.setCategory("MALL");
            site.setStatus(1);
            siteMapper.insert(site);

            BizCabinet cabinet = new BizCabinet();
            cabinet.setSiteId(site.getId());
            cabinet.setCabinetNo(cabinetNo);
            cabinet.setName("对账柜机");
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
        Object[] customers = customerIds.toArray();
        for (Long tenant : List.of(TENANT, OTHER_TENANT)) {
            jdbc.update("delete from biz_delay_task where tenant_id = ?", tenant);
        }
        jdbc.update("delete from biz_fault_event where cabinet_id = ?", cabinetId);
        if (!customerIds.isEmpty()) {
            String in = String.join(",", customerIds.stream().map(id -> "?").toList());
            jdbc.update("delete from biz_point_txn where customer_id in (" + in + ")", customers);
            jdbc.update("delete from biz_point_account where customer_id in (" + in + ")", customers);
        }
        jdbc.update("delete from biz_deposit where order_id in (select id from biz_storage_order where cabinet_id = ?)", cabinetId);
        jdbc.update("delete from biz_storage_order where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_compartment where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_cabinet where id = ?", cabinetId);
        jdbc.update("delete from biz_site where tenant_id = ? and site_code = ?", TENANT, "R-" + cabinetNo);
        customerIds.clear();
        TenantContext.clear();
    }

    private Long newCustomer() {
        Long id = 970000L + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 90000);
        customerIds.add(id);
        return id;
    }

    /** 充值 + 下单，返回订单行；走业务路径而不是手搓 insert，这样押金/格口/快照都是真的。 */
    private BizStorageOrder newOrder(Long customerId) {
        TenantContext.runAs(TENANT, () -> points.recharge(customerId, 5000L,
                "CHG-" + UUID.randomUUID(), "对账用例 funding"));
        String orderNo = TenantContext.callAs(TENANT, () -> orderService.create(customerId,
                new CreateOrderCommand(UUID.randomUUID().toString(), cabinetNo, "SMALL", 60))).orderNo();
        return TenantContext.callAs(TENANT, () -> orderMapper.selectOne(
                Wrappers.<BizStorageOrder>lambdaQuery().eq(BizStorageOrder::getOrderNo, orderNo)));
    }

    private ReconcileReport run() {
        return reconcile.reconcile(TENANT);
    }

    @Test
    @DisplayName("健康现场：账平、押金不悬挂、格口与订单一致，报告必须是全 0")
    void healthyTenantReportsClean() {
        Long customerId = newCustomer();
        newOrder(customerId);

        ReconcileReport report = run();

        assertTrue(report.clean(), "不该有任何差异，实际=" + report.failedChecks());
        assertTrue(report.accountsScanned() >= 1, "至少要扫到本用例建的账户");
        // 全 0 也要把每一项列出来：靠“没有这一项”表示通过，等于让新增检查项静默失效
        assertEquals(7, report.diffs().size(), "七个检查项都要有结论，包括 0 的");
    }

    @Test
    @DisplayName("余额被改坏：报出不平并给出样本，且绝不自动把钱改平")
    void imbalanceIsReportedNotFixed() {
        Long customerId = newCustomer();
        newOrder(customerId);
        jdbc.update("update biz_point_account set points = points + 7 where customer_id = ?", customerId);

        ReconcileReport report = run();
        assertEquals(1L, report.countOf(ReconcileService.LEDGER_IMBALANCE), "只应有这一张不平的账");
        long balance = TenantContext.callAs(TENANT, () -> points.accountOf(customerId).getPoints());
        long recalc = TenantContext.callAs(TENANT, () -> points.recalculate(customerId)[0]);
        assertEquals(7L, balance - recalc, "对账只报告：跑完账本还得是错的，修钱必须留人工痕迹");

        ReconcileReport again = run();
        assertEquals(1L, again.countOf(ReconcileService.LEDGER_IMBALANCE),
                "重复跑不会把差异“跑没了”——它是幂等读数，不是补偿");
    }

    @Test
    @DisplayName("没有任何流水的账户，余额非 0 就必须被抓到（漏算这一类最恶性）")
    void balanceWithoutTxnIsCaught() {
        Long customerId = newCustomer();
        TenantContext.runAs(TENANT, () -> points.ensureAccount(customerId));
        jdbc.update("update biz_point_account set points = 88 where customer_id = ?", customerId);

        assertEquals(1L, run().countOf(ReconcileService.LEDGER_IMBALANCE),
                "流水侧一行都没有时 sum 必须按 0 参与比较，而不是跳过这一账户");
    }

    @Test
    @DisplayName("活动单的 HELD 押金是正常态：判据写宽就等于把真问题埋掉")
    void heldDepositOnLiveOrderIsNotHanging() {
        Long customerId = newCustomer();
        BizStorageOrder order = newOrder(customerId);

        // 押金确实在挂着，但单还活着 —— 这是设计内状态
        BizDeposit deposit = TenantContext.callAs(TENANT, () -> depositMapper.selectOne(
                Wrappers.<BizDeposit>lambdaQuery().eq(BizDeposit::getOrderId, order.getId())));
        assertEquals("HELD", deposit.getStatus().name());
        assertEquals(0L, run().countOf(ReconcileService.HANGING_DEPOSIT),
                "在线单不该被报成悬挂押金，否则日均 3 万单时每轮都对出几万条假阳性");

        // 单终态了押金还没退 —— 这才是不变量 4 要抓的
        jdbc.update("update biz_storage_order set status = 'CLOSED', active_flag = null where id = ?", order.getId());
        assertEquals(1L, run().countOf(ReconcileService.HANGING_DEPOSIT), "终态单 + HELD 押金必须报出来");
    }

    @Test
    @DisplayName("有单无位：活动单的格口回到 FREE 时必须报出来")
    void orderWithoutSlotIsCaught() {
        Long customerId = newCustomer();
        BizStorageOrder order = newOrder(customerId);

        assertEquals(0L, run().countOf(ReconcileService.ORDER_WITHOUT_SLOT), "刚建好的现场不该有漂移");
        jdbc.update("update biz_compartment set status = 'FREE', current_order_id = null where id = ?",
                order.getSlotId());
        assertEquals(1L, run().countOf(ReconcileService.ORDER_WITHOUT_SLOT), "活动单的格口回 FREE 是脏状态");
        // 注：这个现场下 uk_order_active_slot 会阻止别人再分配该格口（第 8 刀的结构防线）。
        // 所以本用例不再建第二张单：被破坏的现场本来就该“卡住”而不是“接着用”，不能把它当可忽略的干扰。
    }

    @Test
    @DisplayName("有位无单：格口标着占用而单已终态，这个位置从此谁也用不了")
    void slotWithoutOrderIsCaught() {
        Long customerId = newCustomer();
        BizStorageOrder order = newOrder(customerId);

        // 正常路径下单终态时格口已被 release 回 FREE；这里只把单关掉而不释放格口
        jdbc.update("update biz_storage_order set status = 'CLOSED', active_flag = null where id = ?", order.getId());
        jdbc.update("update biz_compartment set status = 'OCCUPIED' where id = ?", order.getSlotId());
        assertEquals(1L, run().countOf(ReconcileService.SLOT_WITHOUT_ORDER),
                "占用标记不能没有活动单撑着（这种漂移不报错、只少卖，不专门数就永远看不到）");
    }

    @Test
    @DisplayName("滞留单进差异清单：看管可以收口，但人必须还能数出来")
    void strandedOrdersAreCounted() {
        Long customerId = newCustomer();
        BizStorageOrder order = newOrder(customerId);
        jdbc.update("update biz_storage_order set status = 'EXPIRED' where id = ?", order.getId());

        ReconcileReport report = run();
        assertEquals(1L, report.countOf(ReconcileService.STRANDED_ORDER));
        assertFalse(report.clean(), "有滞留就不算对账通过");
    }

    @Test
    @DisplayName("批大小 2 也要扫完全部账户：分批不能漏、不能重")
    void batchingCoversEveryAccount() {
        for (int i = 0; i < 5; i++) {
            Long customerId = newCustomer();
            TenantContext.runAs(TENANT, () -> points.ensureAccount(customerId));
        }

        long scanned = run().accountsScanned();

        // 本类建的账户 + 可能存在的历史账户都在同一租户里；关键不是具体数字而是"扫完了"
        assertTrue(scanned >= 5, "batchSize=2 时游标必须翻完，实际扫描=" + scanned);
        Long total = jdbc.queryForObject(
                "select count(*) from biz_point_account where tenant_id = ? and deleted = 0", Long.class, TENANT);
        assertEquals(total, Long.valueOf(scanned), "扫描行数必须等于该租户账户总数（不漏不重）");
    }

    @Test
    @DisplayName("别的租户的不平不污染本租户结论（join 上拦截器不可依赖，只能自己写条件）")
    void otherTenantImbalanceDoesNotLeak() {
        Long mine = newCustomer();
        newOrder(mine);
        Long theirs = newCustomer();
        customerIds.add(theirs);
        // 在另一个租户里造一张明确的坏账（充值后直接改余额，不需要 ADJUST 那一步）
        TenantContext.runAs(OTHER_TENANT, () -> points.recharge(theirs, 100L,
                "CHG-OTHER-" + UUID.randomUUID(), "越租对照"));
        jdbc.update("update biz_point_account set points = points + 500 where customer_id = ?", theirs);

        ReconcileReport report = run();

        assertEquals(0L, report.countOf(ReconcileService.LEDGER_IMBALANCE),
                "本租户必须干净；对不上说明 SQL 的租户条件是漏的或加错了侧");
        assertEquals(1L, reconcile.reconcile(OTHER_TENANT).countOf(ReconcileService.LEDGER_IMBALANCE),
                "另一个租户确实不平 —— 证明上一条不是“两边都没扫到”造成的假绿");
    }

    @Test
    @DisplayName("对账依赖的索引必须还在（列序查 information_schema，不查优化器心情）")
    void requiredIndexesExist() {
        // 小表上 EXPLAIN 的 key 选择不稳定（第 8 刀实测），所以断言"索引结构能支撑这条查询"而不是"用了它"
        assertIndex("biz_point_txn", "idx_txn_customer", List.of("customer_id", "create_time"));
        assertIndex("biz_deposit", "uk_deposit_order", List.of("order_id"));
        assertIndex("biz_deposit", "idx_deposit_status_time", List.of("status", "held_at"));
        assertIndex("biz_compartment", "idx_slot_current_order", List.of("current_order_id"));
        assertIndex("biz_storage_order", "uk_order_active_slot", List.of("slot_id", "active_flag"));
    }

    private void assertIndex(String table, String index, List<String> columns) {
        List<String> actual = jdbc.queryForList(
                "select column_name from information_schema.statistics where table_schema = database()"
                        + " and table_name = ? and index_name = ? order by seq_in_index", String.class, table, index);
        assertEquals(columns.stream().map(String::toLowerCase).toList(),
                actual.stream().map(String::toLowerCase).toList(),
                table + "." + index + " 的列序变了，对账查询要重新评估");
    }
}
