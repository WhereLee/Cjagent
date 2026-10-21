package com.wherelee.cabinet.storage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.notify.api.NoticePort;
import com.wherelee.cabinet.storage.app.PublicPickupService;
import com.wherelee.cabinet.storage.app.StorageOrderService;
import com.wherelee.cabinet.storage.app.StorageQueryService;
import com.wherelee.cabinet.storage.app.VoucherCodes;
import com.wherelee.cabinet.storage.api.CreateOrderCommand;
import com.wherelee.cabinet.storage.api.StorageOrderView;
import com.wherelee.cabinet.storage.api.VoucherView;
import com.wherelee.cabinet.common.api.ResultCode;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 取件凭据交付闭环（第 25 刀）。
 *
 * <p>这一刀修的是一条"有锁没钥匙"的链路：码会生成、会校验、错五次会锁定，但没有任何
 * 渠道把码交到用户手上——凭码取件事实上不可达。用例因此全部围绕"码到了谁手里、
 * 换码之后旧码还算不算数"来断言，而不是再测一遍校验逻辑（那部分在
 * {@code SingleSlotAndVoucherTest} 里已经钉住）。
 *
 * <p>造数走真实下单链路：交付发生在下单里，手工插订单测不到它。
 */
@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest(properties = {
        "cabinet.scheduler.poll-enabled=false",
        "cabinet.scheduler.hold-grace-minutes=0",
        "cabinet.door-watch.first-remind-minutes=0",
})
@DisplayName("取件凭据：交付、查询、重置即解锁、凭证消息只留一条")
class VoucherDeliveryTest {

    private static final long FUNDING = 215L * 4;
    private static final Long TENANT = 8127L;

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
    private com.wherelee.cabinet.funds.app.PointAccountService points;
    @Autowired
    private StorageOrderService orderService;
    @Autowired
    private com.wherelee.cabinet.storage.app.StorageOrderFacade orderFacade;
    @Autowired
    private StorageQueryService queries;
    @Autowired
    private PublicPickupService publicPickup;
    @Autowired
    private NoticePort notices;

    private JdbcTemplate jdbc;
    private String cabinetNo;
    private Long cabinetId;
    private final Set<Long> customerIds = ConcurrentHashMap.newKeySet();
    private final java.util.Map<Long, String> phoneOf = new ConcurrentHashMap<>();

    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(dataSource);
        cabinetNo = "CAB-V-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        TenantContext.runAs(TENANT, () -> {
            BizSite site = new BizSite();
            site.setSiteCode("V-" + cabinetNo);
            site.setName("凭据点位");
            site.setCategory("MALL");
            site.setStatus(1);
            siteMapper.insert(site);

            BizCabinet cabinet = new BizCabinet();
            cabinet.setSiteId(site.getId());
            cabinet.setCabinetNo(cabinetNo);
            cabinet.setName("凭据柜机");
            cabinet.setModelId(90001L);
            cabinet.setCabinetStatus(CabinetStatus.ENABLED);
            cabinet.setOnlineState(OnlineState.ONLINE);
            cabinetMapper.insert(cabinet);
            cabinetId = cabinet.getId();

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
        jdbc.update("delete from biz_notice where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_delay_task where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_fault_event where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_item_evidence where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_device_report where order_id in (select id from biz_storage_order where cabinet_id = ?)", cabinetId);
        jdbc.update("delete from biz_device_command where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_deposit where order_id in (select id from biz_storage_order where cabinet_id = ?)", cabinetId);
        jdbc.update("delete from biz_storage_order where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_compartment where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_cabinet where id = ?", cabinetId);
        jdbc.update("delete from biz_site where tenant_id = ? and site_code = ?", TENANT, "V-" + cabinetNo);
        if (!customerIds.isEmpty()) {
            Object[] customers = customerIds.toArray();
            String marks = String.join(",", customerIds.stream().map(id -> "?").toList());
            jdbc.update("delete from biz_point_txn where customer_id in (" + marks + ")", customers);
            jdbc.update("delete from biz_point_account where customer_id in (" + marks + ")", customers);
            jdbc.update("delete from biz_customer where id in (" + marks + ")", customers);
        }
        customerIds.clear();
        TenantContext.clear();
    }

    private Long newCustomer() {
        Long id = 996000L + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 15000);
        customerIds.add(id);
        String phone = "1391" + String.format("%07d", Math.abs(id % 10000000));
        phoneOf.put(id, phone);
        jdbc.update("insert into biz_customer (id, tenant_id, open_id, nickname, phone, status, register_time, create_time, update_time, deleted) "
                        + "values (?, ?, ?, '凭据客户', ?, 1, now(3), now(3), now(3), 0)",
                id, TENANT, "tk-" + id, phone);
        TenantContext.runAs(TENANT, () -> points.recharge(id, FUNDING, "CHG-" + UUID.randomUUID(), "凭据用例 funding"));
        return id;
    }

    private StorageOrderView place(Long customerId) {
        // 走真实 HTTP 链同一入口并关门：下单+开门进 OPENING 后，取件（结算）要求
        // 先过 CLOSE_VERIFY 进 ACTIVE——状态机不许 OPENING 直接跳 SETTLING。
        // 只造一张"开过门并关好"的活单，后面的取件与重置才能面对真实现场
        StorageOrderView placed = TenantContext.callAs(TENANT, () -> orderFacade.place(customerId,
                new CreateOrderCommand(UUID.randomUUID().toString(), cabinetNo, SizeType.SMALL.name(), 60)));
        door(placed.orderNo(), customerId, CommandAction.CLOSE_VERIFY);
        return placed;
    }

    private void door(String orderNo, Long customerId, CommandAction action) {
        TenantContext.runAs(TENANT, () -> orderService.openDoor(customerId, orderNo, action));
    }

    private BizStorageOrder order(String orderNo) {
        return TenantContext.callAs(TENANT, () -> orderMapper.selectOne(
                Wrappers.<BizStorageOrder>lambdaQuery().eq(BizStorageOrder::getOrderNo, orderNo)));
    }

    private String tailOf(Long customerId) {
        String phone = phoneOf.get(customerId);
        return phone.substring(phone.length() - 4);
    }

    private String noticeContent(String dedupKey) {
        return jdbc.queryForObject("select content from biz_notice where tenant_id = ? and dedup_key = ?",
                String.class, TENANT, dedupKey);
    }

    private long noticeCount(String dedupKey) {
        Long count = jdbc.queryForObject("select count(*) from biz_notice where tenant_id = ? and dedup_key = ?",
                Long.class, TENANT, dedupKey);
        return count == null ? 0L : count;
    }

    @Test
    @DisplayName("存件那一刻码就交到用户手上：下单视图带码，站内凭证消息同步落库")
    void createDeliversTheVoucher() {
        Long customerId = newCustomer();
        StorageOrderView view = place(customerId);

        assertNotNull(view.message(), "存件响应必须带交付话术——码此前从不出口");
        assertTrue(view.message().matches("(?s).*V\\d{8}.*"), "话术里要有 8 位码：" + view.message());

        String code = order(view.orderNo()).getVoucherCode();
        assertTrue(code != null && code.matches("V\\d{8}"), "落库码同样是 8 位：" + code);
        assertTrue(view.message().contains(code), "响应里的码必须就是库里那张单的码");

        String content = noticeContent(view.orderNo() + ":voucher");
        assertNotNull(content, "站内凭证消息要能回看");
        assertTrue(content.contains(code), "凭证消息含当前码：" + content);
    }

    @Test
    @DisplayName("凭据查询只交给本人、只交活单；他人查与写路径同一口径")
    void voucherQueryRevealsOnlyToOwnerOfAliveOrder() {
        Long customerId = newCustomer();
        String orderNo = place(customerId).orderNo();
        Long other = newCustomer();

        VoucherView mine = TenantContext.callAs(TENANT, () -> queries.voucher(customerId, orderNo));
        assertEquals(order(orderNo).getVoucherCode(), mine.voucherCode());
        assertEquals(cabinetNo, mine.cabinetNo());
        assertTrue(!mine.locked(), "新单不该是锁定态");

        BizException notYours = assertThrows(BizException.class,
                () -> TenantContext.runAs(TENANT, () -> queries.voucher(other, orderNo)));
        assertEquals(ResultCode.RESOURCE_NOT_FOUND, notYours.getResultCode());
        assertTrue(notYours.getMessage().contains("不存在"), "不区分“不存在”与“不是你的”：" + notYours.getMessage());

        TenantContext.runAs(TENANT, () -> orderService.pickup(customerId, orderNo));
        BizException terminal = assertThrows(BizException.class,
                () -> TenantContext.runAs(TENANT, () -> queries.voucher(customerId, orderNo)));
        assertTrue(terminal.getMessage().contains("已失效"), "终态不回显用不了的码：" + terminal.getMessage());
    }

    @Test
    @DisplayName("重置即自救：旧码作废、新码可取、错次清零，且只有一条凭证消息")
    void resetInvalidatesOldCodeAndKeepsOneCredentialNotice() {
        Long customerId = newCustomer();
        String orderNo = place(customerId).orderNo();
        String oldCode = order(orderNo).getVoucherCode();
        String tail = tailOf(customerId);

        VoucherView reset = TenantContext.callAs(TENANT, () -> orderService.resetVoucher(customerId, orderNo));
        String newCode = reset.voucherCode();
        assertNotNull(newCode);
        assertTrue(newCode.matches("V\\d{8}"));
        assertNotEquals(oldCode, newCode, "重置必须换码");

        // 旧码作废：按码查活单已经查不到这张单，走的是与"码不存在"同一句失败话术
        BizException stale = assertThrows(BizException.class,
                () -> TenantContext.callAs(TENANT, () -> publicPickup.pickup(oldCode, tail)));
        assertTrue(stale.getMessage().contains("不正确"), stale.getMessage());

        // 新码可用，并完成取件（双因子那条路径）
        TenantContext.runAs(TENANT, () -> publicPickup.pickup(newCode, tail));
        assertNotNull(order(orderNo).getFinishedAt(), "新码必须能真的取件");

        assertEquals(1, noticeCount(orderNo + ":voucher"), "改写而不是再发一条：消息页不许堆废码");
        assertTrue(noticeContent(orderNo + ":voucher").contains(newCode), "凭证消息要反映当前码");
    }

    @Test
    @DisplayName("锁定态自助重置即解锁：第 25 刀拍板的出路，旧口径只给客服一条路")
    void resetUnlocksAFiveWrongAttemptsOrder() {
        Long customerId = newCustomer();
        String orderNo = place(customerId).orderNo();
        String code = order(orderNo).getVoucherCode();

        // 错码要发在**这张真单**上才会计数：匿名路径按码找不到单，走的是“码不存在”同一句话，
        // 不进错次——锁定计数只属于被反复试的那张单
        for (int i = 0; i < 5; i++) {
            String bad = String.format("V%08d", 10000000 + i);
            assertThrows(BizException.class,
                    () -> TenantContext.callAs(TENANT, () -> orderService.pickup(customerId, orderNo, bad)));
        }
        BizStorageOrder locked = order(orderNo);
        assertNotNull(locked.getVoucherLockedAt(), "连错 5 次应写下锁定时刻");
        assertEquals(5, locked.getVoucherWrongCount().intValue());

        // 锁定期间不许开柜（出路文案指向"重置"，本用例断言它真的能走通）
        BizException doorBlocked = assertThrows(BizException.class,
                () -> TenantContext.runAs(TENANT, () -> orderService.openDoor(customerId, orderNo,
                        CommandAction.OPEN_TEMP)));
        assertTrue(doorBlocked.getMessage().contains("重置"), "锁定话术要指向自助出路：" + doorBlocked.getMessage());

        VoucherView reset = TenantContext.callAs(TENANT, () -> orderService.resetVoucher(customerId, orderNo));
        BizStorageOrder after = order(orderNo);
        assertNull(after.getVoucherLockedAt(), "重置即解锁");
        assertEquals(0, after.getVoucherWrongCount().intValue(), "错次必须清零");
        assertNotEquals(code, reset.voucherCode());
        assertTrue(!reset.locked());

        // 解锁后凭新码正常取件
        TenantContext.runAs(TENANT, () -> publicPickup.pickup(reset.voucherCode(), tailOf(customerId)));
        assertNotNull(order(orderNo).getFinishedAt(), "解锁后东西应能拿走");
    }

    @Test
    @DisplayName("终态单不许重置；生成口径只有一个（两位数以上都不许出现第二处定义）")
    void resetRejectsTerminalAndCodeShapeIsSingular() {
        Long customerId = newCustomer();
        String orderNo = place(customerId).orderNo();
        TenantContext.runAs(TENANT, () -> orderService.pickup(customerId, orderNo));

        BizException terminal = assertThrows(BizException.class,
                () -> TenantContext.runAs(TENANT, () -> orderService.resetVoucher(customerId, orderNo)));
        assertTrue(terminal.getMessage().contains("已失效"), terminal.getMessage());

        for (int i = 0; i < 50; i++) {
            assertTrue(VoucherCodes.newVoucher().matches("V\\d{8}"), "8 位是唯一口径");
        }
    }
}

