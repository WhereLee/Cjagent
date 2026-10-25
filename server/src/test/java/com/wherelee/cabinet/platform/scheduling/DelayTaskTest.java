package com.wherelee.cabinet.platform.scheduling;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.locker.api.Sensing;
import com.wherelee.cabinet.locker.app.CompartmentStateService;
import com.wherelee.cabinet.storage.app.StorageOrderService;
import com.wherelee.cabinet.storage.jobs.OverduePickupHandler;
import com.wherelee.cabinet.storage.api.CreateOrderCommand;
import com.wherelee.cabinet.platform.scheduling.DelayTaskService;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.locker.domain.BizCabinet;
import com.wherelee.cabinet.locker.domain.BizCompartment;
import com.wherelee.cabinet.domain.entity.BizDelayTask;
import com.wherelee.cabinet.funds.domain.BizDeposit;
import com.wherelee.cabinet.device.domain.BizFaultEvent;
import com.wherelee.cabinet.locker.domain.BizSite;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.locker.api.CabinetStatus;
import com.wherelee.cabinet.locker.api.CompartmentAnomaly;
import com.wherelee.cabinet.funds.api.DepositStatus;
import com.wherelee.cabinet.device.api.FaultType;
import com.wherelee.cabinet.locker.api.OnlineState;
import com.wherelee.cabinet.domain.enums.OrderStatus;
import com.wherelee.cabinet.locker.api.Presence;
import com.wherelee.cabinet.locker.api.SizeType;
import com.wherelee.cabinet.locker.api.SlotStatus;
import com.wherelee.cabinet.domain.enums.TaskStatus;
import com.wherelee.cabinet.domain.enums.TaskType;
import com.wherelee.cabinet.locker.infra.BizCabinetMapper;
import com.wherelee.cabinet.locker.infra.BizCompartmentMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizDelayTaskMapper;
import com.wherelee.cabinet.funds.infra.BizDepositMapper;
import com.wherelee.cabinet.device.infra.BizFaultEventMapper;
import com.wherelee.cabinet.locker.infra.BizSiteMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import com.wherelee.cabinet.funds.app.PointAccountService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest(properties = {
        "cabinet.scheduler.poll-enabled=false",
        "cabinet.scheduler.reconcile-minutes=0",
        "cabinet.scheduler.batch-size=50",
        "cabinet.scheduler.backoff-base-seconds=1"
})
class DelayTaskTest {

    private static final Long TENANT = 8101L;

    @Autowired
    private DelayTaskService tasks;
    @Autowired
    private BizDelayTaskMapper taskMapper;
    @Autowired
    private StorageOrderService orderService;
    @Autowired
    private CompartmentStateService states;
    @Autowired
    private OverduePickupHandler overdueHandler;
    @Autowired
    private PointAccountService points;
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
    private BizFaultEventMapper faultMapper;
    @Autowired
    private TransactionTemplate txTemplate;
    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private Long cabinetId;
    private String cabinetNo;
    private final Set<Long> customerIds = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("delete from biz_delay_task");
        cabinetNo = "CAB-S-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        TenantContext.runAs(TENANT, () -> {
            BizSite site = new BizSite();
            site.setSiteCode("S-" + cabinetNo);
            site.setName("调度点位");
            site.setCategory("MALL");
            site.setStatus(1);
            siteMapper.insert(site);

            BizCabinet cabinet = new BizCabinet();
            cabinet.setSiteId(site.getId());
            cabinet.setCabinetNo(cabinetNo);
            cabinet.setName("调度柜机");
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
        jdbc.update("delete from biz_delay_task where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_fault_event where cabinet_id = ?", cabinetId);
        if (!customerIds.isEmpty()) {
            jdbc.update("delete from biz_point_txn where customer_id in (" + placeholders() + ")", customers);
            jdbc.update("delete from biz_point_account where customer_id in (" + placeholders() + ")", customers);
        }
        jdbc.update("delete from biz_deposit where order_id in (select id from biz_storage_order where cabinet_id = ?)", cabinetId);
        jdbc.update("delete from biz_storage_order where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_compartment where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_cabinet where id = ?", cabinetId);
        jdbc.update("delete from biz_site where tenant_id = ? and site_code = ?", TENANT, "S-" + cabinetNo);
        customerIds.clear();
        TenantContext.clear();
    }

    private String placeholders() {
        return customerIds.stream().map(id -> "?").reduce((a, b) -> a + "," + b).orElse("?");
    }

    private static final String[] SIZE_ROTATION = {"SMALL", "MEDIUM", "LARGE"};

    private Long newCustomer() {
        Long id = 960000L + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 90000);
        customerIds.add(id);
        return id;
    }

    private BizStorageOrder newOrder(Long customerId) {
        return newOrder(customerId, "SMALL");
    }

    private BizStorageOrder newOrder(Long customerId, String sizeType) {
        TenantContext.runAs(TENANT, () -> points.recharge(customerId, 5000L,
                "CHG-" + UUID.randomUUID(), "调度用例 funding"));
        String orderNo = TenantContext.callAs(TENANT, () -> orderService.create(customerId,
                new CreateOrderCommand(UUID.randomUUID().toString(), cabinetNo, sizeType))).orderNo();
        return TenantContext.callAs(TENANT, () -> orderMapper.selectOne(
                Wrappers.<BizStorageOrder>lambdaQuery().eq(BizStorageOrder::getOrderNo, orderNo)));
    }

    private BizStorageOrder newLegacyDepositOrder(Long customerId, long depositPoints) {
        BizStorageOrder order = newOrder(customerId);
        var frozen = TenantContext.callAs(TENANT, () -> points.freeze(customerId, depositPoints,
                "ORDER", order.getId(), order.getOrderNo() + ":legacy-deposit", "历史每单押金夹具"));
        BizDeposit deposit = new BizDeposit();
        deposit.setOrderId(order.getId());
        deposit.setCustomerId(customerId);
        deposit.setPoints(depositPoints);
        deposit.setStatus(DepositStatus.HELD);
        deposit.setHeldTxnId(frozen.txnId());
        deposit.setHeldAt(LocalDateTime.now());
        TenantContext.runAs(TENANT, () -> depositMapper.insert(deposit));
        jdbc.update("update biz_storage_order set frozen_points = ? where id = ?",
                depositPoints, order.getId());
        order.setFrozenPoints(depositPoints);
        return order;
    }

    private void scheduleNow(TaskType type, String bizKey) {
        TenantContext.runAs(TENANT, () -> tasks.schedule(type, bizKey, TENANT, LocalDateTime.now().minusSeconds(5)));
    }

    private BizStorageOrder activate(Long customerId, BizStorageOrder order) {
        TenantContext.runAs(TENANT, () -> orderService.openDoor(customerId, order.getOrderNo(),
                com.wherelee.cabinet.device.api.CommandAction.OPEN));
        TenantContext.runAs(TENANT, () -> orderService.openDoor(customerId, order.getOrderNo(),
                com.wherelee.cabinet.device.api.CommandAction.CLOSE_VERIFY));
        return TenantContext.callAs(TENANT, () -> orderMapper.selectById(order.getId()));
    }

    private void backdateDeadline(Long orderId, long minutesAgo) {
        jdbc.update("update biz_storage_order set expected_finish_at = date_sub(now(3), interval ? minute) where id = ?",
                minutesAgo, orderId);
    }

    private BizDelayTask task(TaskType type, String bizKey) {
        return TenantContext.callAs(TENANT, () -> taskMapper.selectOne(
                Wrappers.<BizDelayTask>lambdaQuery()
                        .eq(BizDelayTask::getTaskType, type)
                        .eq(BizDelayTask::getBizKey, bizKey)));
    }

    @Test
    @DisplayName("下单自动登记超时任务；到点后走业务取消路径释放格口与资金")
    void slotReleaseUsesBusinessPath() {
        Long customerId = newCustomer();
        BizStorageOrder order = newOrder(customerId);
        assertNotNull(task(TaskType.SLOT_RELEASE, order.getOrderNo()), "下单应自动登记超时释放任务");

        int executed = TenantContext.callAs(TENANT, tasks::runDue);
        assertTrue(executed > 0, "到期的超时任务应被执行");

        BizStorageOrder after = TenantContext.callAs(TENANT, () -> orderMapper.selectById(order.getId()));
        assertEquals(OrderStatus.CANCELLED, after.getStatus(), "超时未投件应自动取消");
        assertEquals(SlotStatus.FREE, TenantContext.callAs(TENANT,
                () -> slotMapper.selectById(order.getSlotId())).getStatus(), "格口必须释放");
        assertEquals(0L, TenantContext.callAs(TENANT, () -> points.accountOf(customerId)).getFrozenPoints(),
                "冻结（押金 + 预估）必须全部退回");
        assertEquals("", TenantContext.callAs(TENANT, () -> points.verifyLedger(customerId)), "账必须仍平");

        BizDelayTask released = task(TaskType.SLOT_RELEASE, order.getOrderNo());
        assertEquals(TaskStatus.DONE, released.getStatus());
    }

    @Test
    @DisplayName("多 worker 并发抢同一批任务：每张单的业务副作用恰好发生一次")
    void concurrentWorkersNeverDoubleExecute() throws Exception {
        int orders = 12;
        for (int i = 0; i < orders; i++) {
            newOrder(newCustomer(), SIZE_ROTATION[i % SIZE_ROTATION.length]);
        }
        jdbc.update("delete from biz_delay_task where task_type <> 'SLOT_RELEASE'");
        jdbc.update("update biz_delay_task set fire_at = date_sub(now(3), interval 10 second) where task_type='SLOT_RELEASE'");

        int workers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(workers);
        AtomicInteger totalExecuted = new AtomicInteger();

        for (int i = 0; i < workers; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    TenantContext.runAs(TENANT, () -> {
                        long deadline = System.currentTimeMillis() + 60_000L;
                        while (System.currentTimeMillis() < deadline && undoneSlotRelease() > 0) {
                            totalExecuted.addAndGet(tasks.runDue());
                            try {
                                Thread.sleep(50L);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                return;
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
        assertTrue(done.await(120, TimeUnit.SECONDS), "并发 worker 未收敛");
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "worker 线程未退出，会给后续用例留租约竞争");
        pool.shutdownNow();

        Integer notDone = jdbc.queryForObject(
                "select count(*) from biz_delay_task where task_type='SLOT_RELEASE' and status <> 'DONE'", Integer.class);
        assertEquals(0, notDone, "并发下所有到期任务都应最终被完成（不丢不做两遍）");
        Integer fundTxns = jdbc.queryForObject(
                "select count(*) from biz_point_txn t join biz_storage_order o on o.id = t.ref_id "
                        + "where o.cabinet_id = ? and t.biz_type in ('UNFREEZE_OUT','FREEZE_IN','FREEZE_OUT')",
                Integer.class, cabinetId);
        assertEquals(0, fundTxns,
                "C：下单不预冻，回收取消也不该动任何冻结/解冻流水（出现一行就是多做了）");
        assertTrue(totalExecuted.get() >= orders,
                "被抢次数不应少于任务数；实测 " + totalExecuted.get() + "（>任务数部分=合法重试）");
        Integer cancelled = jdbc.queryForObject(
                "select count(distinct order_no) from biz_storage_order where cabinet_id=? and status='CANCELLED'",
                Integer.class, cabinetId);
        assertEquals(orders, cancelled, "每张单都被取消，且没有一张被取消两次");
        for (Long customer : customerIds) {
            assertEquals(0L, TenantContext.callAs(TENANT, () -> points.accountOf(customer)).getFrozenPoints(),
                    "每张单的冻结必须全部退回，不重不漏");
            assertEquals("", TenantContext.callAs(TENANT, () -> points.verifyLedger(customer)),
                    "并发取消后账仍必须平（不平就是多扣或多退）");
        }
    }

    private int undoneSlotRelease() {
        Integer n = jdbc.queryForObject(
                "select count(*) from biz_delay_task where task_type='SLOT_RELEASE' and status <> 'DONE'",
                Integer.class);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("SKIP LOCKED 让两个事务拿到不相交的候选集（不排队等待）")
    void skipLockedGivesDisjointCandidates() throws Exception {
        for (int i = 0; i < 10; i++) {
            newOrder(newCustomer(), SIZE_ROTATION[i % SIZE_ROTATION.length]);
        }
        jdbc.update("delete from biz_delay_task where task_type <> 'SLOT_RELEASE'");
        jdbc.update("update biz_delay_task set fire_at = date_sub(now(3), interval 10 second)");

        List<Long> alone = new java.util.ArrayList<>();
        TenantContext.runAs(TENANT, () -> txTemplate.executeWithoutResult(only -> {
            alone.addAll(taskMapper.findDueIdsForUpdateSkip(TENANT, 4));
            only.setRollbackOnly();
        }));
        assertEquals(4, alone.size(), "无竞争时应拿满 limit（拿不满是索引或到期判据本身坏了）");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch bothInTx = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Set<Long> first = ConcurrentHashMap.newKeySet();
        Set<Long> second = ConcurrentHashMap.newKeySet();
        Map<String, Integer> lockFootprint = new ConcurrentHashMap<>();
        AtomicInteger slot = new AtomicInteger();

        Runnable probe = () -> {
            try {
                txTemplate.executeWithoutResult(status -> {
                    List<Long> ids = taskMapper.findDueIdsForUpdateSkip(TENANT, 4);
                    String who = slot.getAndIncrement() == 0 ? "first" : "second";
                    ("first".equals(who) ? first : second).addAll(ids);
                    lockFootprint.put(who, grantedRecordLocks());
                    bothInTx.countDown();
                    try {
                        release.await(20, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    status.setRollbackOnly();
                });
            } catch (RuntimeException e) {
                throw new IllegalStateException(e);
            }
        };
        pool.submit(probe);
        pool.submit(probe);
        assertTrue(bothInTx.await(20, TimeUnit.SECONDS), "两个事务没能同时进入候选集读取，说明后一个在等行锁");
        release.countDown();
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        Set<Long> overlap = new java.util.HashSet<>(first);
        overlap.retainAll(second);
        assertTrue(overlap.isEmpty(), "两个事务的候选集重叠：" + overlap);
        assertTrue(first.size() + second.size() <= 10, "候选总量不会超过到期任务数（不重复计数）");

        lockFootprint.forEach((who, held) -> {
            int returned = "first".equals(who) ? first.size() : second.size();
            if (held >= 0) {
                assertTrue(held >= returned,
                        who + " 的锁条数不应少于它拿到的行数：returned=" + returned + " held=" + held);
            }
        });
    }

    private int grantedRecordLocks() {
        try {
            Integer count = jdbc.queryForObject("""
                    select count(*) from performance_schema.data_locks l
                      join performance_schema.threads t on t.thread_id = l.thread_id
                     where l.object_name = 'biz_delay_task'
                       and l.lock_type = 'RECORD'
                       and l.lock_status = 'GRANTED'
                       and t.processlist_id = connection_id()
                    """, Integer.class);
            return count == null ? -1 : count;
        } catch (RuntimeException unavailable) {
            return -1;
        }
    }

    @Test
    @DisplayName("租约过期可被接管：实例被 kill 不会让任务永久卡在 RUNNING")
    void expiredLeaseIsTakeOverable() {
        Long customerId = newCustomer();
        BizStorageOrder order = newOrder(customerId);
        scheduleNow(TaskType.DOOR_NOT_CLOSED, order.getOrderNo());
        BizDelayTask planted = task(TaskType.DOOR_NOT_CLOSED, order.getOrderNo());

        jdbc.update("update biz_delay_task set status='RUNNING', lease_owner='dead-worker', "
                + "lease_expire_at = date_sub(now(3), interval 60 second) where id = ?", planted.getId());

        int executed = TenantContext.callAs(TENANT, tasks::runDue);
        assertTrue(executed > 0, "过期租约必须能被接管");

        BizStorageOrder after = TenantContext.callAs(TENANT, () -> orderMapper.selectById(order.getId()));
        BizDelayTask taken = task(TaskType.DOOR_NOT_CLOSED, order.getOrderNo());
        assertEquals(TaskStatus.DONE, taken.getStatus(), "接管后任务要正常收尾");
        assertNotNull(after);
    }

    @Test
    @DisplayName("门开超过容错期：转成计费事件而不是人工工单（S-16）")
    void doorOpenPastToleranceStartsBilling() {
        Long customerId = newCustomer();
        BizStorageOrder order = newOrder(customerId);
        TenantContext.runAs(TENANT, () -> orderService.openDoor(customerId, order.getOrderNo(),
                com.wherelee.cabinet.device.api.CommandAction.OPEN));
        assertEquals(OrderStatus.OPENING, TenantContext.callAs(TENANT,
                () -> orderMapper.selectById(order.getId())).getStatus());

        jdbc.update("update biz_storage_order set tolerance_until = date_sub(now(3), interval 1 second) "
                + "where id = ?", order.getId());
        scheduleNow(TaskType.DOOR_NOT_CLOSED, order.getOrderNo());
        TenantContext.callAs(TENANT, tasks::runDue);

        BizStorageOrder after = TenantContext.callAs(TENANT, () -> orderMapper.selectById(order.getId()));
        assertEquals(OrderStatus.ACTIVE, after.getStatus(),
                "门开超容错应转成计费中，而不是转人工");
        assertNotNull(after.getStartedAt(), "计费起点必须已落定（不落定就是无限免费）");
        assertFalse(after.getStartedAt().isAfter(java.time.LocalDateTime.now()),
                "起点不能落在未来：那等于这一分钟仍然不计费");

        BizCompartment slot = TenantContext.callAs(TENANT, () -> slotMapper.selectById(order.getSlotId()));
        assertEquals(CompartmentAnomaly.DOOR_OPEN, slot.getAnomaly(), "门未关必须标成异常，不得被分给下一位");
        assertNotNull(slot.getDoorOpenAt(), "开门起点要留着：计费时长与提醒都据它");

        BizFaultEvent event = TenantContext.callAs(TENANT, () -> faultMapper.selectOne(
                Wrappers.<BizFaultEvent>lambdaQuery().eq(BizFaultEvent::getCabinetId, cabinetId)
                        .orderByDesc(BizFaultEvent::getId).last("limit 1")));
        assertNotNull(event, "异常必须同时进台账（只写日志等于没人看）");
        assertEquals(FaultType.DOOR_NOT_CLOSED, event.getFaultType());

        BizDelayTask watched = task(TaskType.DOOR_NOT_CLOSED, order.getOrderNo());
        assertEquals(TaskStatus.PENDING, watched.getStatus(), "计费还在走，看管就必须续排");
    }

    @Test
    @DisplayName("门关上后异常自动解除：只看门、不看物")
    void doorClosedClearsAnomalyEvenWithoutItemSensor() {
        Long customerId = newCustomer();
        BizStorageOrder order = newOrder(customerId);
        TenantContext.runAs(TENANT, () -> orderService.openDoor(customerId, order.getOrderNo(),
                com.wherelee.cabinet.device.api.CommandAction.OPEN));
        jdbc.update("update biz_storage_order set tolerance_until = date_sub(now(3), interval 1 second) "
                + "where id = ?", order.getId());
        scheduleNow(TaskType.DOOR_NOT_CLOSED, order.getOrderNo());
        TenantContext.callAs(TENANT, tasks::runDue);
        assertEquals(CompartmentAnomaly.DOOR_OPEN,
                TenantContext.callAs(TENANT, () -> slotMapper.selectById(order.getSlotId())).getAnomaly(),
                "先确认异常真的标上了（不然后面的“解除了”只是从未发生）");

        TenantContext.runAs(TENANT, () -> states.tryAutoRecover(order.getSlotId(),
                new Sensing(true, Presence.UNKNOWN, LocalDateTime.now())));

        assertNull(TenantContext.callAs(TENANT, () -> slotMapper.selectById(order.getSlotId())).getAnomaly(),
                "DOOR_OPEN 的解除判据只能是门本身；“里面有没有东西”是另外两类异常的责任");
    }

    @Test
    @DisplayName("逾期转 EXPIRED 后任务继续被盯着（DONE 就等于再没人看）")
    void overdueTransitsExpiredAndKeepsWatching() {
        Long customerId = newCustomer();
        BizStorageOrder order = activate(customerId, newOrder(customerId));
        backdateDeadline(order.getId(), 120);
        scheduleNow(TaskType.OVERDUE_PICKUP, order.getOrderNo());

        TenantContext.callAs(TENANT, tasks::runDue);

        BizStorageOrder after = TenantContext.callAs(TENANT, () -> orderMapper.selectById(order.getId()));
        assertEquals(OrderStatus.EXPIRED, after.getStatus(), "逾期要转 EXPIRED");
        assertTrue(after.getStatus().canTransitTo(OrderStatus.SETTLING), "EXPIRED 必须还有取件出口");

        BizDelayTask watching = task(TaskType.OVERDUE_PICKUP, order.getOrderNo());
        assertEquals(TaskStatus.PENDING, watching.getStatus(), "未到顶就还得再排一轮");
        assertTrue(watching.getFireAt().isAfter(LocalDateTime.now()),
                "新一轮必须落在未来：已了结(DONE)的任务再登记是新周期的起点，不能被旧的过去时间拽住");
        assertEquals(0, watching.getAttempt(),
                "新一轮的 attempt 必须归零，否则上一轮的失败次数会抵掉这一轮的预算");
    }

    @Test
    @DisplayName("封顶已到：判滞留并停止续排，不能把一张没人接的单排到天荒老")
    void cappedOverdueStopsWatching() {
        Long customerId = newCustomer();
        BizStorageOrder order = activate(customerId, newOrder(customerId));
        backdateDeadline(order.getId(), 5L * 24 * 60);
        jdbc.update("update biz_storage_order set started_at = date_sub(now(3), interval 50 hour) where id = ?",
                order.getId());
        scheduleNow(TaskType.OVERDUE_PICKUP, order.getOrderNo());

        TenantContext.callAs(TENANT, tasks::runDue);

        BizDelayTask done = task(TaskType.OVERDUE_PICKUP, order.getOrderNo());
        Map<String, Object> row = jdbc.queryForMap(
                "select status, fire_at, attempt from biz_delay_task where task_type='OVERDUE_PICKUP' and biz_key = ?",
                order.getOrderNo());
        Map<String, Object> ord = jdbc.queryForMap(
                "select expected_finish_at, started_at, cast(pricing_snapshot as char) snap from biz_storage_order where id = ?",
                order.getId());
        assertEquals(TaskStatus.DONE, done.getStatus(),
                "价格已到顶（cap-days=2，即 48 小时）就不该再排：多盯一轮也不会多一分，只多一条日志；"
                        + "任务行=" + row + " 订单行=" + ord);
        assertEquals(OrderStatus.EXPIRED, TenantContext.callAs(TENANT,
                () -> orderMapper.selectById(order.getId())).getStatus(), "停看管不能把单推到终态");
    }

    @Test
    @DisplayName("封顶不限的旧快照：逾期够 stranded-days 也要收口，否则看管永远没有结")
    void legacyUncappedOrderStillExitsViaWatchWindow() {
        Long customerId = newCustomer();
        BizStorageOrder order = activate(customerId, newOrder(customerId));
        jdbc.update("update biz_storage_order set pricing_snapshot = "
                + "'{\"unitPointsPerHour\":15,\"freeMinutes\":10,\"depositPoints\":200}' where id = ?", order.getId());
        backdateDeadline(order.getId(), 8L * 24 * 60);
        scheduleNow(TaskType.OVERDUE_PICKUP, order.getOrderNo());

        TenantContext.callAs(TENANT, tasks::runDue);

        assertEquals(TaskStatus.DONE, task(TaskType.OVERDUE_PICKUP, order.getOrderNo()).getStatus(),
                "封顶判不出来时 stranded-days 必须接住（默认 7 天，这里逾期 8 天）");
    }

    @Test
    @DisplayName("到顶开单后重跑不再开第二张：transactional()=false 的代价靠可重入兜住")
    void cappedOrderOpensOneWorkOrderEvenIfRerun() {
        assertFalse(overdueHandler.transactional(),
                "会同步调工单系统的 handler 不能被外层事务包住");

        Long customerId = newCustomer();
        BizStorageOrder order = activate(customerId, newOrder(customerId));
        backdateDeadline(order.getId(), 5L * 24 * 60);
        jdbc.update("update biz_storage_order set started_at = date_sub(now(3), interval 50 hour) where id = ?",
                order.getId());

        scheduleNow(TaskType.OVERDUE_PICKUP, order.getOrderNo());
        TenantContext.callAs(TENANT, tasks::runDue);

        int opened = countLongRunningWorkOrders(order.getSlotId());
        assertEquals(1, opened, "到顶必须交给人：开一张线上 LONG_RUNNING 单");

        scheduleNow(TaskType.OVERDUE_PICKUP, order.getOrderNo());
        TenantContext.callAs(TENANT, tasks::runDue);

        assertEquals(opened, countLongRunningWorkOrders(order.getSlotId()),
                "重跑不得堆出第二张单（去重靠 dedup_key 与状态条件）");
        assertEquals(OrderStatus.EXPIRED, TenantContext.callAs(TENANT,
                () -> orderMapper.selectById(order.getId())).getStatus(),
                "重跑也不能把单推回别的状态：EXPIRED 不是终态，还有取件出口");
    }

    private int countLongRunningWorkOrders(Long slotId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from biz_work_order where tenant_id = ? and slot_id = ? and type = 'LONG_RUNNING'",
                Integer.class, TENANT, slotId);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("失败按指数退避重试，超上限才判 DEAD（不是无限重试也不是判死太早）")
    void retryUsesBackoffThenDies() {
        Long customerId = newCustomer();
        BizStorageOrder order = newOrder(customerId);
        jdbc.update("delete from biz_delay_task where task_type <> 'DEPOSIT_REFUND'");
        scheduleNow(TaskType.DEPOSIT_REFUND, order.getOrderNo());

        TenantContext.callAs(TENANT, tasks::runDue);
        BizDelayTask afterFirst = task(TaskType.DEPOSIT_REFUND, order.getOrderNo());
        assertEquals(TaskStatus.FAILED, afterFirst.getStatus(), "失败后应回到待重试而不是直接判死");
        assertEquals(1, afterFirst.getAttempt());
        assertTrue(afterFirst.getFireAt().isAfter(LocalDateTime.now()), "退避应把下次时间推到未来");

        for (int i = 0; i < TaskType.DEPOSIT_REFUND.maxAttempts(); i++) {
            jdbc.update("update biz_delay_task set fire_at = date_sub(now(3), interval 10 second) where id = ?",
                    afterFirst.getId());
            TenantContext.callAs(TENANT, tasks::runDue);
        }
        BizDelayTask dead = task(TaskType.DEPOSIT_REFUND, order.getOrderNo());
        assertEquals(TaskStatus.DEAD, dead.getStatus(), "重试用尽要判死并等人，而不是无限重试打爆日志");
        assertTrue(dead.getAttempt() >= TaskType.DEPOSIT_REFUND.maxAttempts(),
                "attempt 必须真实累加，否则退避形同不存在");
    }

    @Test
    @DisplayName("押金悬挂安全网：终态单被回收，重跑不重复退钱")
    void depositSafetyNetIsIdempotent() {
        Long customerId = newCustomer();
        BizStorageOrder order = newLegacyDepositOrder(customerId, 200L);
        BizDeposit deposit = TenantContext.callAs(TENANT, () -> depositMapper.selectOne(
                Wrappers.<BizDeposit>lambdaQuery().eq(BizDeposit::getOrderId, order.getId())));
        assertNotNull(deposit);
        assertEquals(DepositStatus.HELD, deposit.getStatus());

        jdbc.update("update biz_storage_order set status='CLOSED', active_flag=null where id = ?", order.getId());
        scheduleNow(TaskType.DEPOSIT_REFUND, order.getOrderNo());
        TenantContext.callAs(TENANT, tasks::runDue);

        BizDeposit first = TenantContext.callAs(TENANT, () -> depositMapper.selectById(deposit.getId()));
        assertEquals(DepositStatus.REFUNDED, first.getStatus());
        long pointsAfterFirst = TenantContext.callAs(TENANT, () -> points.accountOf(customerId)).getPoints();

        jdbc.update("update biz_delay_task set status='PENDING', fire_at = date_sub(now(3), interval 10 second) "
                + "where task_type='DEPOSIT_REFUND' and biz_key=?", order.getOrderNo());
        TenantContext.callAs(TENANT, tasks::runDue);

        assertEquals(pointsAfterFirst, TenantContext.callAs(TENANT,
                () -> points.accountOf(customerId)).getPoints(), "重试不能把押金退第二次");
        assertEquals("", TenantContext.callAs(TENANT, () -> points.verifyLedger(customerId)));
    }

    @Test
    @DisplayName("登记是幂等的：重复登记只会把到期时间提前，不会堆出多条")
    void scheduleIsIdempotent() {
        Long customerId = newCustomer();
        BizStorageOrder order = newOrder(customerId);
        int before = countTasks(TaskType.SLOT_RELEASE, order.getOrderNo());

        scheduleNow(TaskType.SLOT_RELEASE, order.getOrderNo());
        scheduleNow(TaskType.SLOT_RELEASE, order.getOrderNo());

        assertEquals(before, countTasks(TaskType.SLOT_RELEASE, order.getOrderNo()),
                "同一 (类型,业务键) 只能有一条任务，否则超时会被反复登记成多条");
    }

    private int countTasks(TaskType type, String bizKey) {
        return jdbc.queryForObject("select count(*) from biz_delay_task where task_type=? and biz_key=?",
                Integer.class, type.name(), bizKey);
    }
}
