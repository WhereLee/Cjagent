package com.wherelee.cabinet;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.application.point.OrderFundService;
import com.wherelee.cabinet.application.point.PointAccountService;
import com.wherelee.cabinet.application.point.RechargeService;
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
import com.wherelee.cabinet.infrastructure.mapper.BizLockerConsoleMapper;
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
 * 欠费清偿（补缴）链路。
 *
 * <p>这组用例存在的原因是第 14 刀上线时<b>只做了"欠费挡单"，没做"还完放行"</b>：
 * 拒单文案写着"请先在点数页补缴后再下单"，可系统里根本没有补缴这条路（充值只加余额、不碰订单上的欠额），
 * 于是一次余额不足就变成永久禁单。规则本身没错，错在只实现了拦、没实现出口。
 *
 * <p>两条最要紧的断言：
 * ① <b>不足额时一分钱都不扣</b>——"吃掉 50 点还是不能下单"是最容易被投诉的形态；
 * ② <b>还完必须真的能下单</b>——走真实充值链路验，不是手工 UPDATE 一个 0 出来。
 *
 * <p>造欠费的方式全部走业务动作（下单→开柜→关门→把计费起点前移→取件结算），
 * 因为"结算补扣不足"本身就是要覆盖的分支；只有<b>时间</b>用 SQL 前移（计费以服务端时间为准，
 * 不能为了测试去改时钟）。
 */
@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest(properties = {
        "cabinet.scheduler.poll-enabled=false",
        "cabinet.scheduler.hold-grace-minutes=0",
        "cabinet.pricing.tolerance-minutes=0",
})
@DisplayName("欠费清偿：不足额不动钱、足额自动放行、先欠先还、重复触发不双扣")
class ArrearsRepayFlowTest {

    /** 默认价（dev 配置）：押金 200 + 小格口 15 点/小时；封顶 3 天 × 12 小时 = 36 小时。 */
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

    /** 新客户默认不带余额：余额由用例自己决定（造欠费需要"刚好只够冻结"的金额）。 */
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
                new CreateOrderCommand(UUID.randomUUID().toString(), cabinetNo, "SMALL", 60))).orderNo();
    }

    /** 走完"开门 → 关门起计"，返回已进入计费中的单号。 */
    private String openAndClose(Long customerId, String orderNo) {
        TenantContext.runAs(TENANT, () -> orderService.openDoor(customerId, orderNo, CommandAction.OPEN));
        TenantContext.runAs(TENANT, () -> orderService.openDoor(customerId, orderNo, CommandAction.CLOSE_VERIFY));
        return orderNo;
    }

    /**
     * 把计费起点前移 100 小时后取件：应缴被封顶成 36 小时 × 15 = 540，
     * 而冻结只有 215，于是结算真实走到"补扣不足 → 记欠费"这一支。
     */
    private BizStorageOrder settleIntoArrears(Long customerId, String orderNo) {
        jdbc.update("update biz_storage_order set started_at = date_sub(now(3), interval 100 hour) where order_no = ?", orderNo);
        TenantContext.runAs(TENANT, () -> orderService.pickup(customerId, orderNo));
        BizStorageOrder settled = order(orderNo);
        assertNotNull(settled.getFinishedAt(), "前置数据没铺好：单没结算");
        assertTrue(settled.getArrearsPoints() != null && settled.getArrearsPoints() > 0,
                "前置数据没铺好：结算没产生欠费，arrears=" + settled.getArrearsPoints());
        return settled;
    }

    /** 一张真实欠费单：只给冻结额，结算必然不够。 */
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
                () -> orderService.create(customerId, new CreateOrderCommand(UUID.randomUUID().toString(), cabinetNo, "SMALL", 60))));
        assertTrue(blocked.getMessage().contains("未缴清"), blocked.getMessage());
        assertTrue(blocked.getMessage().contains("补缴"), "文案指向的动作必须真的存在：" + blocked.getMessage());

        // 走真实充值链路：第一笔不够，第二笔才补足
        TenantContext.runAs(TENANT, () -> rechargeService.recharge(customerId, 200L));
        assertTrue(order(owed.getOrderNo()).getArrearsPoints() > 0, "没还完就不该放行");

        TenantContext.runAs(TENANT, () -> rechargeService.recharge(customerId, 200L));
        assertEquals(0L, order(owed.getOrderNo()).getArrearsPoints(), "补足后欠额必须归零");
        assertEquals(1, repayTxnCount(customerId, owed.getOrderNo()), "清偿要留下一条流水");

        // 清偿会真的把余额扣掉（400-325=75），所以再补一笔“新单需要的冻结额”，
        // 否则这一步会因“余额不够冻结”失败——那不是本用例要证的事
        fund(customerId, FREEZE_DEFAULT);
        assertNotNull(TenantContext.callAs(TENANT, () -> orderService.create(customerId,
                new CreateOrderCommand(UUID.randomUUID().toString(), cabinetNo, "SMALL", 60))),
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
    @DisplayName("先欠的先还：一次充值只够一张时，按结算时间先后抵扣")
    void repaysOldestOrderFirst() {
        Long customerId = newCustomer();
        // 两张单要在产生欠费之前都下好：否则第二张会被自己的欠费拦截挡住（那正是拦截该有的行为）
        fund(customerId, FREEZE_DEFAULT * 2);
        String first = openAndClose(customerId, createOrder(customerId));
        String second = openAndClose(customerId, createOrder(customerId));
        BizStorageOrder o1 = settleIntoArrears(customerId, first);
        BizStorageOrder o2 = settleIntoArrears(customerId, second);
        // 结算时间拉开两小时，让"先欠先还"有确定的顺序可断言
        jdbc.update("update biz_storage_order set finished_at = date_sub(now(3), interval 2 hour) where id = ?", o1.getId());
        jdbc.update("update biz_storage_order set finished_at = date_sub(now(3), interval 1 hour) where id = ?", o2.getId());

        long each = o1.getArrearsPoints();
        fund(customerId, each);
        long repaid = TenantContext.callAs(TENANT, () -> funds.repayArrears(customerId));

        assertEquals(each, repaid, "余额刚好够一张时应只还一张");
        assertEquals(0L, order(first).getArrearsPoints(), "先结算的那张先还");
        assertEquals(each, order(second).getArrearsPoints(), "后一张不该被越过");

        List<BizStorageOrder> stillOwed = TenantContext.callAs(TENANT, () -> orderMapper.listOwed(customerId));
        assertEquals(1, stillOwed.size());
        assertEquals(o2.getId(), stillOwed.get(0).getId());
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
        // 台账查询也要租户上下文（缺上下文的无租户条件 SQL 会被守卫直接拒执），
        // 拦截、台账、清偿三者必须走同一条 sumCustomerArrears
        assertTrue(TenantContext.callAs(TENANT, () -> consoleMapper.sumCustomerArrears(customerId)) > 0,
                "台账应先显示他欠着");

        fund(customerId, owed.getArrearsPoints());
        TenantContext.runAs(TENANT, () -> funds.repayArrears(customerId));

        assertEquals(0L, TenantContext.callAs(TENANT, () -> consoleMapper.sumCustomerArrears(customerId)),
                "拦截、台账、清偿必须共用同一条 SQL 口径，否则会出现“后台说没欠、下单却被拒”");
    }
}
