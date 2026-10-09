package com.wherelee.cabinet;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.application.storage.SlotAllocator;
import com.wherelee.cabinet.application.storage.StorageOrderFacade;
import com.wherelee.cabinet.application.storage.StorageOrderService;
import com.wherelee.cabinet.application.storage.dto.CreateOrderCommand;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.entity.BizCabinet;
import com.wherelee.cabinet.domain.entity.BizCompartment;
import com.wherelee.cabinet.domain.entity.BizSite;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.CabinetStatus;
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

/**
 * 格口分配并发行为基类：两种策略跑<b>同一套</b>断言，对比才有意义。
 *
 * <p>核心断言不是"性能多好"，而是三条不变量：
 * ① 成功数 == 可用格口数（不多不少）；② 同一格口不存在两条活动单；
 * ③ 失败方拿到的一定是 10409/10410 而不是 500（"抢不到"是业务结果，不是系统故障）。
 *
 * <p>这里<b>不加 @Transactional</b>：测试事务不会传播到工作线程，加了只会造成
 * "看起来回滚了、其实工作线程各开各的事务"的错觉。数据靠 @AfterEach 精确清理。
 */
abstract class AbstractAllocationBehaviourTest {

    protected static final Long TENANT = 8101L;
    protected static final int THREADS = 20;
    /** 模板里大格口 6 个：请求 LARGE 时，最多只能成功 6 单 */
    protected static final int LARGE_SLOTS = 6;

    @Autowired
    protected StorageOrderService storageOrderService;
    /** 并发用例必须走门面（真实入口）：直接调 Service 会跳过柜机锁，redisson 策略就测了个寂寞。 */
    @Autowired
    protected StorageOrderFacade facade;
    @Autowired
    protected com.wherelee.cabinet.application.point.PointAccountService points;
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

    /** 子类需要拿柜机 ID 去定位锁等外部资源（字段本身保持 private）。 */
    protected Long currentCabinetId() {
        return cabinetId;
    }

    /** 柜机锁是按 cabinetNo 加的，子类要校同一个 key。 */
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
        // 顺序：先单后格口再柜机点位；都用物理删（测试夹具，不留逻辑删除残行干扰计数）
        jdbc.update("delete from biz_storage_order where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_compartment where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_cabinet where id = ?", cabinetId);
        jdbc.update("delete from biz_site where tenant_id = ? and site_code like 'S-CAB-T%'", TENANT);
        TenantContext.clear();
    }

    /** 每个线程一个独立事务 + 独立租户上下文（TenantContext 是 ThreadLocal，不会自动带到线程池）。 */
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
                    // requestId 每线程唯一：不能被幂等注解当作同一请求合并，否则测的是幂等不是并发
                    String requestId = "rt-" + UUID.randomUUID();
                    Object outcome = TenantContext.callAs(TENANT, () -> {
                        fund(900001L + seq);
                        return facade.create(900001L + seq,
                                new CreateOrderCommand(requestId, cabinetNo, requested.name(), 120));
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

    /**
     * 竞争强度：默认 20 线程抢 6 口（故意超卖压力）；
     * 有界等锁的策略（redisson）会有一部分请求主动放弃，这是设计行为而不是 bug。
     */
    @Test
    @DisplayName("N 个线程抢有限格口：不超卖、不脏数据、失败都是业务码")
    void concurrentAllocationNeverOversells() throws Exception {
        List<Object> results = runConcurrentRequests(THREADS, SizeType.LARGE);

        long success = results.stream()
                .filter(r -> r instanceof com.wherelee.cabinet.application.storage.dto.StorageOrderView).count();
        List<BizException> failures = results.stream()
                .filter(r -> r instanceof BizException)
                .map(r -> (BizException) r)
                .toList();

        // 硬不变量：成功数不得超过容量（超卖）、不得静默丢请求
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

    /**
     * 容量刚好等于线程数：此时不该有人等待，三种策略都必须全部成功。
     * 这条才是“恰等于”的确定断言，而不是在 20 抢 6 的场景里硬要求全抢到。
     */
    @Test
    @DisplayName("线程数恰等于容量：全部成功抢到，无等待放弃")
    void allocationAtCapacityAllSucceed() throws Exception {
        List<Object> results = runConcurrentRequests(LARGE_SLOTS, SizeType.LARGE);
        long success = results.stream()
                .filter(r -> r instanceof com.wherelee.cabinet.application.storage.dto.StorageOrderView).count();
        assertEquals(LARGE_SLOTS, success,
                "容量内无竞争，不应有任何请求失败（策略=" + allocator.strategy() + "）：" + results);
        assertNoOversell();
    }

    /** 子类声明自己在“线程数=容量”下应成功多少，防止基类容量改动后期望静默失配。 */
    protected int expectedSuccessesAtCapacity() {
        return LARGE_SLOTS;
    }

    /** 共用的一组存储层不变量取证（失败时报错会带上策略名，便于定位是哪条路径）。 */
    private void assertNoOversell() {
        // 不变量①：同一格口的活动单数必须 <= 1
        Integer doubleBooked = jdbc.queryForObject(
                "select count(*) from (select slot_id from biz_storage_order where active_flag = 1 "
                        + "group by slot_id having count(*) > 1) t", Integer.class);
        assertEquals(0, doubleBooked, "出现同一格口多条活动单：超卖（策略=" + allocator.strategy() + "）");

        // 不变量②：格口状态与订单必须一致（RESERVED 的格口都要有 current_order_id）
        Integer inconsistent = jdbc.queryForObject(
                "select count(*) from biz_compartment where cabinet_id = ? "
                        + "and status = 'RESERVED' and current_order_id is null", Integer.class, cabinetId);
        assertEquals(0, inconsistent, "格口显示占用却没绑订单");

        // 不变量③（库存守恒）：本刀只有 FREE/RESERVED 两态，两者相加必须等于总数；
        // 出现“消失的格口”就是分配代码泄了状态（例如 CAS 抢到一半异常却没回滚）
        int total = countByStatus(null);
        int accounted = countByStatus(SlotStatus.FREE) + countByStatus(SlotStatus.RESERVED);
        assertEquals(total, accounted, "库存守恒被破坏：FREE + RESERVED != 总数（策略="
                + allocator.strategy() + "）");
    }

    /**
     * 建单现在要先有钱（押金 + 预估冻结）。
     *
     * <p><b>辅助方法自己包租户上下文</b>：“记得包一层”这件事在这个项目里已经踩过五次，
     * 靠自律不如靠默认安全。
     */
    protected void fund(long customerId) {
        TenantContext.runAs(TENANT, () -> points.recharge(customerId, 20000L,
                "FUND-" + customerId + "-" + UUID.randomUUID(), "压测/用例 funding"));
    }
    
    /** status 传 null 表示统计该机全部格口。必须包在租户上下文里：断言跑在主线程，而租户守卫会拒无上下文的查询。 */
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
                900777L, new CreateOrderCommand("cancel-" + UUID.randomUUID(), cabinetNo, "LARGE", 60)));
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
