package com.wherelee.cabinet;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.application.storage.CompartmentStateService;
import com.wherelee.cabinet.application.storage.StorageOrderService;
import com.wherelee.cabinet.application.storage.dto.CreateOrderCommand;
import com.wherelee.cabinet.application.task.DelayTaskService;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.domain.entity.BizCabinet;
import com.wherelee.cabinet.domain.entity.BizCompartment;
import com.wherelee.cabinet.domain.entity.BizDelayTask;
import com.wherelee.cabinet.domain.entity.BizDeposit;
import com.wherelee.cabinet.domain.entity.BizFaultEvent;
import com.wherelee.cabinet.domain.entity.BizSite;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.CabinetStatus;
import com.wherelee.cabinet.domain.enums.CompartmentAnomaly;
import com.wherelee.cabinet.domain.enums.DepositStatus;
import com.wherelee.cabinet.domain.enums.FaultType;
import com.wherelee.cabinet.domain.enums.OnlineState;
import com.wherelee.cabinet.domain.enums.OrderStatus;
import com.wherelee.cabinet.domain.enums.Presence;
import com.wherelee.cabinet.domain.enums.SizeType;
import com.wherelee.cabinet.domain.enums.SlotStatus;
import com.wherelee.cabinet.domain.enums.TaskStatus;
import com.wherelee.cabinet.domain.enums.TaskType;
import com.wherelee.cabinet.infrastructure.mapper.BizCabinetMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizCompartmentMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizDelayTaskMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizDepositMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizFaultEventMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizSiteMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import com.wherelee.cabinet.application.point.PointAccountService;
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

/**
 * 延迟任务与调度（第 13 刀验收）。
 *
 * <p>关掉后台轮询（{@code poll-enabled=false}），用例自己调 {@code runDue()}：
 * 否则"偶尔红"的测试不是稳定性问题而是不可测。
 *
 * <p>这一刀真正要证明的只有一件事：<b>不管有几个 worker、提醒重了几次，
 * 同一个任务的业务副作用只能发生一次</b>。所以并发用例看的是"每个 bizKey 恰好执行一次"，
 * 而不是"跑完了没报错"。
 */
@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest(properties = {
        "cabinet.scheduler.poll-enabled=false",
        // 回收阈值归零：否则下单登记的任务在未来，用例测的是“等待”而不是“执行”
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
        // 每个 id 一个 ?：把 id 直拼进 SQL 又同时传参，驱动会报“Parameter index out of range”
        return customerIds.stream().map(id -> "?").reduce((a, b) -> a + "," + b).orElse("?");
    }

    private static final String[] SIZE_ROTATION = {"SMALL", "MEDIUM", "LARGE"};

    private Long newCustomer() {
        Long id = 960000L + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 90000);
        customerIds.add(id);
        return id;
    }

    /** 建一张真实订单（走业务路径，含押金冻结与自动登记的超时任务）。 */
    private BizStorageOrder newOrder(Long customerId) {
        return newOrder(customerId, "SMALL");
    }

    /** 指定尺寸建单（一人一单 + 尺寸不再向上升级后，一批单必须分摊到三种格口） */
    private BizStorageOrder newOrder(Long customerId, String sizeType) {
        // 充值也必须带租户上下文：守卫把“无上下文”当成“拒绝执行”，而不是“查不到”（第 10 刀同源结论）
        TenantContext.runAs(TENANT, () -> points.recharge(customerId, 5000L,
                "CHG-" + UUID.randomUUID(), "调度用例 funding"));
        String orderNo = TenantContext.callAs(TENANT, () -> orderService.create(customerId,
                new CreateOrderCommand(UUID.randomUUID().toString(), cabinetNo, sizeType, 60))).orderNo();
        return TenantContext.callAs(TENANT, () -> orderMapper.selectOne(
                Wrappers.<BizStorageOrder>lambdaQuery().eq(BizStorageOrder::getOrderNo, orderNo)));
    }

    private void scheduleNow(TaskType type, String bizKey) {
        // fire_at 放到过去：等价于"闹钟已经响了"，测的是执行而不是等待
        TenantContext.runAs(TENANT, () -> tasks.schedule(type, bizKey, TENANT, LocalDateTime.now().minusSeconds(5)));
    }

    /** 把单推到 ACTIVE（真开柜 + 关门校验），没这个就不能测“计费中遇逾期”这条真实链路。 */
    private BizStorageOrder activate(Long customerId, BizStorageOrder order) {
        TenantContext.runAs(TENANT, () -> orderService.openDoor(customerId, order.getOrderNo(),
                com.wherelee.cabinet.domain.enums.CommandAction.OPEN));
        TenantContext.runAs(TENANT, () -> orderService.openDoor(customerId, order.getOrderNo(),
                com.wherelee.cabinet.domain.enums.CommandAction.CLOSE_VERIFY));
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
            // 每人一格（定-5），且不能指望“小格满了自动给中格”（定-8）：
            // 所以这里用 12 个不同客户 + 三种尺寸分摊，刚好用完柜机 4+4+4 个格口
            newOrder(newCustomer(), SIZE_ROTATION[i % SIZE_ROTATION.length]);
        }
        // 只留超时释放这一类，避免混入别的类型干扰计数
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
                    // 每个 worker 都在自己的租户上下文里跑（与真实实例一致）
                    TenantContext.runAs(TENANT, () -> {
                        // 跑到“没完”而不是“跑固定轮数”：一次瞬时冲突会让任务进入 FAILED + 退避，
                        // 固定 3 轮在 2 核 CI 上就会把“还没轮到重试”算成失败（本地快、CI 慢就是这类飘红）
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
        // 必须等线程真终止：只 shutdownNow 就接着跑下一个用例，残留线程会在新用例开始后
        // 抢走它的任务租约（60s），症状是下一个用例“什么都不发生”——本刀就这么把
        // doorNotClosedMarksAbnormal 弄跳红了。测并发的用例不能给后面留活线程。
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "worker 线程未退出，会给后续用例留租约竞争");
        pool.shutdownNow();

        // 真正要证的不是“跑了几次”，而是“副作用发生了几次”：跑的次数会被合法重试污染，
        // 而一张单的解冻出账只允许一条流水（双执行会多一条，或被 uk_txn_biz / 状态机当场拦下）
        Integer notDone = jdbc.queryForObject(
                "select count(*) from biz_delay_task where task_type='SLOT_RELEASE' and status <> 'DONE'", Integer.class);
        assertEquals(0, notDone, "并发下所有到期任务都应最终被完成（不丢不做两遍）");
        Integer unfreezeOut = jdbc.queryForObject(
                "select count(*) from biz_point_txn t join biz_storage_order o on o.id = t.ref_id "
                        + "where o.cabinet_id = ? and t.biz_type = 'UNFREEZE_OUT'", Integer.class, cabinetId);
        assertEquals(orders, unfreezeOut,
                "每张单只能解冻一次：多了就是双执行（这是本用例的硬证据）");
        assertTrue(totalExecuted.get() >= orders,
                "被抢次数不应少于任务数；实测 " + totalExecuted.get() + "（>任务数部分=合法重试）");
        Integer cancelled = jdbc.queryForObject(
                "select count(distinct order_no) from biz_storage_order where cabinet_id=? and status='CANCELLED'",
                Integer.class, cabinetId);
        assertEquals(orders, cancelled, "每张单都被取消，且没有一张被取消两次");
        // 一人一单后这 12 张单属 12 个不同客户，所以逐个核：冻结全退、账仍平
        for (Long customer : customerIds) {
            assertEquals(0L, TenantContext.callAs(TENANT, () -> points.accountOf(customer)).getFrozenPoints(),
                    "每张单的冻结必须全部退回，不重不漏");
            assertEquals("", TenantContext.callAs(TENANT, () -> points.verifyLedger(customer)),
                    "并发取消后账仍必须平（不平就是多扣或多退）");
        }
    }

    /** 本轮还没做完的超时释放任务数（驱动 worker 跑到收敛）。 */
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

        // 无竞争的单独取证：先证明“这条 SQL 真的能拿满 limit”。
        // 删掉“至少一方拿到候选”后，这一条是必验项：否则两个事务都拿到空集时，
        // “不重叠”与“不排队”会在一件根本没返行的事上成立（假绿）
        List<Long> alone = new java.util.ArrayList<>();
        TenantContext.runAs(TENANT, () -> txTemplate.executeWithoutResult(only -> {
            alone.addAll(taskMapper.findDueIdsForUpdateSkip(4));
            only.setRollbackOnly();   // 只取证：本用例不该推任何任务状态
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
                    // 每人只要 4 条（共 10 条到期）：limit 大于行数时 SKIP LOCKED 会合法地让
                    // 后一个拿到空集，那是对的行为，不能当成失败去断言
                    List<Long> ids = taskMapper.findDueIdsForUpdateSkip(4);
                    String who = slot.getAndIncrement() == 0 ? "first" : "second";
                    ("first".equals(who) ? first : second).addAll(ids);
                    // 锁取证必须在同一个事务里读：回滚后锁就没了（第 10 刀欠的“可重复取证”补在这里）
                    lockFootprint.put(who, grantedRecordLocks());
                    bothInTx.countDown();
                    try {
                        // 两个事务都要先拿到候选集再一起放行，否则观测不到“是否重叠”
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
        // 两个事务都进入候选集读取后再放行；若 SKIP LOCKED 不生效，第二个会阻塞在行锁上直到本行 commit
        assertTrue(bothInTx.await(20, TimeUnit.SECONDS), "两个事务没能同时进入候选集读取，说明后一个在等行锁");
        release.countDown();
        pool.shutdownNow();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        Set<Long> overlap = new java.util.HashSet<>(first);
        overlap.retainAll(second);
        assertTrue(overlap.isEmpty(), "两个事务的候选集重叠：" + overlap);
        // 不断言“各自拿到 4 条”，也**不断言“至少一方拿到候选”**：这 10 条任务的 fire_at 完全相同，
        // InnoDB 走二级索引扫描时锁的是 next-key 区间而不是行，两个扫描者都可能把整个区间当成
        // “被别人锁住”而合法地拿到空集（本地快、2 核 runner 慢，交错不同就会飘）。
        // “谁拿到几条”不是 SKIP LOCKED 的承诺；本用例并发部分只证两件事：
        // ① 不重叠（上面）；② 不排队——两个事务能在彼此持锁期间同时返回（bothInTx latch）。
        // 而“这条 SQL 确实能拿满候选”放到下面无竞争的单独取证里去断言（结论确定，不依赖交错）
        assertTrue(first.size() + second.size() <= 10, "候选总量不会超过到期任务数（不重复计数）");

        // 取证结论：持有的记录锁条数 ≥ 返回行数——next-key/gap 锁把不相干的行（与行之间的间隙）
        // 也圈进来了。这正是“第二个扫描者只拿到 1 条”的解释：它不是丢数据，是碰上了别人的锁区间。
        lockFootprint.forEach((who, held) -> {
            int returned = "first".equals(who) ? first.size() : second.size();
            if (held >= 0) {
                assertTrue(held >= returned,
                        who + " 的锁条数不应少于它拿到的行数：returned=" + returned + " held=" + held);
            }
        });
    }

    /**
     * 本连接在 biz_delay_task 上已持有的记录锁条数（performance_schema 不可用时返回 -1，
     * 取证降级但不能拖垮主断言）。
     */
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

        // 手工造成"上一个持有者死了"：RUNNING + 租约已过期
        jdbc.update("update biz_delay_task set status='RUNNING', lease_owner='dead-worker', "
                + "lease_expire_at = date_sub(now(3), interval 60 second) where id = ?", planted.getId());

        int executed = TenantContext.callAs(TENANT, tasks::runDue);
        assertTrue(executed > 0, "过期租约必须能被接管");

        BizStorageOrder after = TenantContext.callAs(TENANT, () -> orderMapper.selectById(order.getId()));
        // 订单还在 OPENING 之前（未开柜），所以门未关任务不该改状态；只要求租约推进完成
        BizDelayTask taken = task(TaskType.DOOR_NOT_CLOSED, order.getOrderNo());
        assertEquals(TaskStatus.DONE, taken.getStatus(), "接管后任务要正常收尾");
        assertNotNull(after);
    }

    @Test
    @DisplayName("门开超过容错期：转成计费事件而不是人工工单（S-16）")
    void doorOpenPastToleranceStartsBilling() {
        Long customerId = newCustomer();
        BizStorageOrder order = newOrder(customerId);
        // 走到 OPENING：开柜成功（模拟器正常回执）
        TenantContext.runAs(TENANT, () -> orderService.openDoor(customerId, order.getOrderNo(),
                com.wherelee.cabinet.domain.enums.CommandAction.OPEN));
        assertEquals(OrderStatus.OPENING, TenantContext.callAs(TENANT,
                () -> orderMapper.selectById(order.getId())).getStatus());

        // 把容错到期时刻也推到过去：真实运行中巡检不会提前跑，而“现在就是到期后的第一眼”
        // 才是这条用例要描述的时刻。不推就会拿一个未来的起点去断言，测不到真东西。
        jdbc.update("update biz_storage_order set tolerance_until = date_sub(now(3), interval 1 second) "
                + "where id = ?", order.getId());
        scheduleNow(TaskType.DOOR_NOT_CLOSED, order.getOrderNo());
        TenantContext.callAs(TENANT, tasks::runDue);

        BizStorageOrder after = TenantContext.callAs(TENANT, () -> orderMapper.selectById(order.getId()));
        // 旧口径是“转 ABNORMAL 等人工”。现在：全城多点位派一次人工的成本远高于一个格口被占的损耗，
        // 所以不关门靠钱来处置：起计、推进到计费态、进台账，而不是给人派活
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

        // 到顶之前这条看管不能停：“DONE”就是再没人盯，而用户可能永远不回来关门
        BizDelayTask watched = task(TaskType.DOOR_NOT_CLOSED, order.getOrderNo());
        assertEquals(TaskStatus.PENDING, watched.getStatus(), "计费还在走，看管就必须续排");
    }

    @Test
    @DisplayName("门关上后异常自动解除：只看门、不看物")
    void doorClosedClearsAnomalyEvenWithoutItemSensor() {
        Long customerId = newCustomer();
        BizStorageOrder order = newOrder(customerId);
        TenantContext.runAs(TENANT, () -> orderService.openDoor(customerId, order.getOrderNo(),
                com.wherelee.cabinet.domain.enums.CommandAction.OPEN));
        jdbc.update("update biz_storage_order set tolerance_until = date_sub(now(3), interval 1 second) "
                + "where id = ?", order.getId());
        scheduleNow(TaskType.DOOR_NOT_CLOSED, order.getOrderNo());
        TenantContext.callAs(TENANT, tasks::runDue);
        assertEquals(CompartmentAnomaly.DOOR_OPEN,
                TenantContext.callAs(TENANT, () -> slotMapper.selectById(order.getSlotId())).getAnomaly(),
                "先确认异常真的标上了（不然后面的“解除了”只是从未发生）");

        // 拿一个“门关了但柜内测不到”的读数去解除：现实里大多数柜机只有门磁没有物检，
        // 如果把“测不到”也算成解除阻碍，整柜会越用越多地永久卡在异常里。
        // runAs 不能省：服务内部要读格口，没有租户上下文会被守卫直接拒执（本会话第 6 次踩这条）
        TenantContext.runAs(TENANT, () -> states.tryAutoRecover(order.getSlotId(),
                new CompartmentStateService.Sensing(true, Presence.UNKNOWN, LocalDateTime.now())));

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
        // 逾期不等于“东西不要了”：仍必须能取件结算
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
        scheduleNow(TaskType.OVERDUE_PICKUP, order.getOrderNo());

        TenantContext.callAs(TENANT, tasks::runDue);

        BizDelayTask done = task(TaskType.OVERDUE_PICKUP, order.getOrderNo());
        assertEquals(TaskStatus.DONE, done.getStatus(),
                "价格已到顶（cap-days=3）就不该再排：多盯一轮也不会多一分，只多一条日志");
        assertEquals(OrderStatus.EXPIRED, TenantContext.callAs(TENANT,
                () -> orderMapper.selectById(order.getId())).getStatus(), "停看管不能把单推到终态");
    }

    @Test
    @DisplayName("封顶不限的旧快照：逾期够 stranded-days 也要收口，否则看管永远没有结")
    void legacyUncappedOrderStillExitsViaWatchWindow() {
        Long customerId = newCustomer();
        BizStorageOrder order = activate(customerId, newOrder(customerId));
        // 换成不含阶梯参数的旧快照（capDays 读不到 = 永远到不了顶），只能靠看管窗口收口
        jdbc.update("update biz_storage_order set pricing_snapshot = "
                + "'{\"unitPointsPerHour\":15,\"freeMinutes\":10,\"depositPoints\":200}' where id = ?", order.getId());
        backdateDeadline(order.getId(), 8L * 24 * 60);
        scheduleNow(TaskType.OVERDUE_PICKUP, order.getOrderNo());

        TenantContext.callAs(TENANT, tasks::runDue);

        assertEquals(TaskStatus.DONE, task(TaskType.OVERDUE_PICKUP, order.getOrderNo()).getStatus(),
                "封顶判不出来时 stranded-days 必须接住（默认 7 天，这里逾期 8 天）");
    }

    @Test
    @DisplayName("失败按指数退避重试，超上限才判 DEAD（不是无限重试也不是判死太早）")
    void retryUsesBackoffThenDies() {
        Long customerId = newCustomer();
        BizStorageOrder order = newOrder(customerId);
        // 只留押金这一类：否则同批的超时释放会先把单取消，押金任务第一次就成功，
        // “未终态→退避重试”这条路径根本跑不到（测了个寂寞）
        jdbc.update("delete from biz_delay_task where task_type <> 'DEPOSIT_REFUND'");
        // 押金退还任务在订单未终态时必须"稍后再试"——handler 主动抛出让它退避
        scheduleNow(TaskType.DEPOSIT_REFUND, order.getOrderNo());

        TenantContext.callAs(TENANT, tasks::runDue);
        BizDelayTask afterFirst = task(TaskType.DEPOSIT_REFUND, order.getOrderNo());
        assertEquals(TaskStatus.FAILED, afterFirst.getStatus(), "失败后应回到待重试而不是直接判死");
        assertEquals(1, afterFirst.getAttempt());
        assertTrue(afterFirst.getFireAt().isAfter(LocalDateTime.now()), "退避应把下次时间推到未来");

        // 反复"到期→执行"，直到超过上限
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

    @org.junit.jupiter.api.Disabled("每单押金已随定-1 作废（新单不再产生押金行），本用例要重写为“历史单”场景："
            + "先插一行 HELD 凭证 + 带押金的冻结与流水，再跑安全网。重写完之前宁可显式关掉，也不让它默默变绿")
    @Test
    @DisplayName("押金悬挂安全网：终态单被回收，重跑不重复退钱")
    void depositSafetyNetIsIdempotent() {
        Long customerId = newCustomer();
        BizStorageOrder order = newOrder(customerId);
        BizDeposit deposit = TenantContext.callAs(TENANT, () -> depositMapper.selectOne(
                Wrappers.<BizDeposit>lambdaQuery().eq(BizDeposit::getOrderId, order.getId())));
        assertNotNull(deposit);
        assertEquals(DepositStatus.HELD, deposit.getStatus());

        // 手工制造悬挂现场：订单已关闭、押金仍 HELD、冻结栏还压着钱（正常路径不会这样）
        jdbc.update("update biz_storage_order set status='CLOSED', active_flag=null where id = ?", order.getId());
        scheduleNow(TaskType.DEPOSIT_REFUND, order.getOrderNo());
        TenantContext.callAs(TENANT, tasks::runDue);

        BizDeposit first = TenantContext.callAs(TENANT, () -> depositMapper.selectById(deposit.getId()));
        assertEquals(DepositStatus.REFUNDED, first.getStatus());
        long pointsAfterFirst = TenantContext.callAs(TENANT, () -> points.accountOf(customerId)).getPoints();

        // 再跑一轮：必须什么都不做（重复退钱是真金白银损失）
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
