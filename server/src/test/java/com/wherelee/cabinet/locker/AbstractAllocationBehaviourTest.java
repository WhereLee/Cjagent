package com.wherelee.cabinet.locker;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.locker.api.SlotAllocator;
import com.wherelee.cabinet.storage.app.StorageOrderFacade;
import com.wherelee.cabinet.storage.app.StorageOrderService;
import com.wherelee.cabinet.storage.api.CreateOrderCommand;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.locker.domain.BizCabinet;
import com.wherelee.cabinet.locker.domain.BizCompartment;
import com.wherelee.cabinet.locker.domain.BizSite;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.locker.api.CabinetStatus;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

abstract class AbstractAllocationBehaviourTest {

    protected static final Long TENANT = 8101L;
    protected static final int THREADS = 20;
    protected static final int LARGE_SLOTS = 6;

    @Autowired
    protected StorageOrderService storageOrderService;
    @Autowired
    protected StorageOrderFacade facade;
    @Autowired
    protected com.wherelee.cabinet.funds.app.PointAccountService points;
    @Autowired
    protected SlotAllocator allocator;
    @Autowired
    private BizSiteMapper siteMapper;
    @Autowired
    private BizCabinetMapper cabinetMapper;
    @Autowired
    private BizCompartmentMapper slotMapper;
    @Autowired
    private BizStorageOrderMapper orderMapper;
    @Autowired
    private DataSource dataSource;

    private Long cabinetId;
    private String cabinetNo;

    protected Long currentCabinetId() {
        return cabinetId;
    }

    protected String currentCabinetNo() {
        return cabinetNo;
    }

    protected JdbcTemplate jdbc;

    @BeforeEach
    void prepareCabinet() {
        jdbc = new JdbcTemplate(dataSource);
        cabinetNo = "CAB-T-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        TenantContext.runAs(TENANT, () -> {
            BizSite site = new BizSite();
            site.setSiteCode("S-" + cabinetNo);
            site.setName("压测点位");
            site.setCategory("MALL");
            site.setStatus(1);
            siteMapper.insert(site);

            BizCabinet cabinet = new BizCabinet();
            cabinet.setSiteId(site.getId());
            cabinet.setCabinetNo(cabinetNo);
            cabinet.setName("压测柜机");
            cabinet.setModelId(90001L);
            cabinet.setCabinetStatus(CabinetStatus.ENABLED);
            cabinet.setOnlineState(OnlineState.ONLINE);
            cabinetMapper.insert(cabinet);
            cabinetId = cabinet.getId();

            insertSlots(cabinetId, SizeType.LARGE, LARGE_SLOTS);
            insertSlots(cabinetId, SizeType.MEDIUM, 8);
            insertSlots(cabinetId, SizeType.SMALL, 10);
        });
    }

    private void insertSlots(Long cabId, SizeType size, int count) {
        for (int i = 1; i <= count; i++) {
            BizCompartment slot = new BizCompartment();
            slot.setCabinetId(cabId);
            slot.setSlotNo(size.name().charAt(0) + String.format("%02d", i));
            slot.setSizeType(size);
            slot.setStatus(SlotStatus.FREE);
            slotMapper.insert(slot);
        }
    }

    @AfterEach
    void cleanup() {
        if (cabinetId == null) {
            return;
        }
        jdbc.update("delete from biz_storage_order where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_compartment where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_cabinet where id = ?", cabinetId);
        jdbc.update("delete from biz_site where tenant_id = ? and site_code like 'S-CAB-T%'", TENANT);
        TenantContext.clear();
    }

    protected List<Object> runConcurrentRequests(int threads, SizeType requested) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ConcurrentLinkedQueue<Object> results = new ConcurrentLinkedQueue<>();

        for (int i = 0; i < threads; i++) {
            final int seq = i;
            pool.submit(() -> {
                try {
                    start.await();
                    String requestId = "rt-" + UUID.randomUUID();
                    Object outcome = TenantContext.callAs(TENANT, () -> {
                        fund(900001L + seq);
                        return facade.create(900001L + seq,
                                new CreateOrderCommand(requestId, cabinetNo, requested.name()));
                    });
                    results.add(outcome);
                } catch (BizException e) {
                    results.add(e);
                } catch (Exception e) {
                    results.add(new IllegalStateException("非预期异常: " + e, e));
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "并发请求未在 60 秒内收敛，可能死锁在连接池或行锁上");
        pool.shutdownNow();
        return List.copyOf(results);
    }

    @Test
    @DisplayName("夹具容量与子类期望一致（防容量改动后断言静默失配）")
    void capacityMatchesExpectation() {
        assertEquals(LARGE_SLOTS, expectedSuccessesAtCapacity(),
                "基类容量与子类期望不一致，用例会假绿");
    }

    @Test
    @DisplayName("N 个线程抢有限格口：不超卖、不脏数据、失败都是业务码")
    void concurrentAllocationNeverOversells() throws Exception {
        List<Object> results = runConcurrentRequests(THREADS, SizeType.LARGE);

        long success = results.stream()
                .filter(r -> r instanceof com.wherelee.cabinet.storage.api.StorageOrderView).count();
        List<BizException> failures = results.stream()
                .filter(r -> r instanceof BizException)
                .map(r -> (BizException) r)
                .toList();

        assertTrue(success <= LARGE_SLOTS,
                "成功数超过可用容量＝超卖：success=" + success + " 策略=" + allocator.strategy());
        assertEquals(THREADS, success + failures.size(), "每个请求都必须有结果，不能静默丢失");
        for (BizException failure : failures) {
            int code = failure.getResultCode().getCode();
            assertTrue(code == 10409 || code == 10410,
                    "抢位失败只能是无位(10409)或抢输/超时(10410)，实际 code=" + code + " msg=" + failure.getMessage());
        }

        assertNoOversell();
    }

    @Test
    @DisplayName("线程数恰等于容量：全部成功抢到，无等待放弃")
    void allocationAtCapacityAllSucceed() throws Exception {
        List<Object> results = runConcurrentRequests(LARGE_SLOTS, SizeType.LARGE);
        long success = results.stream()
                .filter(r -> r instanceof com.wherelee.cabinet.storage.api.StorageOrderView).count();
        assertEquals(LARGE_SLOTS, success,
                "容量内无竞争，不应有任何请求失败（策略=" + allocator.strategy() + "）：" + results);
        assertNoOversell();
    }

    protected int expectedSuccessesAtCapacity() {
        return LARGE_SLOTS;
    }

    private void assertNoOversell() {
        Integer doubleBooked = jdbc.queryForObject(
                "select count(*) from (select slot_id from biz_storage_order where active_flag = 1 "
                        + "group by slot_id having count(*) > 1) t", Integer.class);
        assertEquals(0, doubleBooked, "出现同一格口多条活动单：超卖（策略=" + allocator.strategy() + "）");

        Integer inconsistent = jdbc.queryForObject(
                "select count(*) from biz_compartment where cabinet_id = ? "
                        + "and status = 'RESERVED' and current_order_id is null", Integer.class, cabinetId);
        assertEquals(0, inconsistent, "格口显示占用却没绑订单");

        int total = countByStatus(null);
        int accounted = countByStatus(SlotStatus.FREE) + countByStatus(SlotStatus.RESERVED);
        assertEquals(total, accounted, "库存守恒被破坏：FREE + RESERVED != 总数（策略="
                + allocator.strategy() + "）");
    }

    protected void fund(long customerId) {
        TenantContext.runAs(TENANT, () -> points.recharge(customerId, 20000L,
                "FUND-" + customerId + "-" + UUID.randomUUID(), "压测/用例 funding"));
    }

    protected int countByStatus(SlotStatus status) {
        return TenantContext.callAs(TENANT, () -> {
            Long count = slotMapper.selectCount(Wrappers.<BizCompartment>lambdaQuery()
                    .eq(BizCompartment::getCabinetId, cabinetId)
                    .eq(status != null, BizCompartment::getStatus, status));
            return count.intValue();
        });
    }

    @Test
    @DisplayName("取消后格口立刻可复用，且不出现 current_order_id 残留")
    void cancelReleasesSlotCleanly() throws Exception {
        fund(900777L);
        var view = TenantContext.callAs(TENANT, () -> storageOrderService.create(
                900777L, new CreateOrderCommand("cancel-" + UUID.randomUUID(), cabinetNo, "LARGE")));
        assertNotNull(view.orderNo());

        TenantContext.runAs(TENANT, () -> storageOrderService.cancel(900777L, view.orderNo()));

        assertEquals(LARGE_SLOTS, countFreeLargeSlots(), "取消后大格口应全部回到 FREE");
        Integer residue = jdbc.queryForObject(
                "select count(*) from biz_compartment where cabinet_id = ? and current_order_id is not null",
                Integer.class, cabinetId);
        assertEquals(0, residue, "释放时没清掉 current_order_id（MP 不写 null 的经典后果）");

        List<BizStorageOrder> active = TenantContext.callAs(TENANT, () -> orderMapper.selectList(
                Wrappers.<BizStorageOrder>lambdaQuery()
                        .eq(BizStorageOrder::getCabinetId, cabinetId)
                        .isNotNull(BizStorageOrder::getActiveFlag)));
        assertTrue(active.isEmpty(), "已取消的单必须退出活动态，否则格口永久不可用");
    }

    private int countFreeLargeSlots() {
        return TenantContext.callAs(TENANT, () -> {
            Long free = slotMapper.selectCount(Wrappers.<BizCompartment>lambdaQuery()
                    .eq(BizCompartment::getCabinetId, cabinetId)
                    .eq(BizCompartment::getSizeType, SizeType.LARGE)
                    .eq(BizCompartment::getStatus, SlotStatus.FREE));
            return free.intValue();
        });
    }
}
