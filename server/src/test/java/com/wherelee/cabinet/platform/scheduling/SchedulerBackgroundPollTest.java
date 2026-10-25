package com.wherelee.cabinet.platform.scheduling;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.funds.app.PointAccountService;
import com.wherelee.cabinet.storage.app.StorageOrderService;
import com.wherelee.cabinet.storage.api.CreateOrderCommand;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.locker.domain.BizCabinet;
import com.wherelee.cabinet.locker.domain.BizCompartment;
import com.wherelee.cabinet.domain.entity.BizDelayTask;
import com.wherelee.cabinet.locker.domain.BizSite;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.locker.api.CabinetStatus;
import com.wherelee.cabinet.locker.api.OnlineState;
import com.wherelee.cabinet.domain.enums.OrderStatus;
import com.wherelee.cabinet.locker.api.SizeType;
import com.wherelee.cabinet.locker.api.SlotStatus;
import com.wherelee.cabinet.domain.enums.TaskStatus;
import com.wherelee.cabinet.domain.enums.TaskType;
import com.wherelee.cabinet.locker.infra.BizCabinetMapper;
import com.wherelee.cabinet.locker.infra.BizCompartmentMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizDelayTaskMapper;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "cabinet.scheduler.poll-enabled=true",
        "cabinet.scheduler.poll-interval-ms=500",
        "cabinet.scheduler.initial-delay-ms=500",
        "cabinet.scheduler.batch-size=20",
        "cabinet.scheduler.reconcile-minutes=0"
})
class SchedulerBackgroundPollTest {

    private static final Long TENANT = 8115L;
    private static final long TIMEOUT_MS = 30_000L;

    @Autowired
    private StorageOrderService orderService;
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
    private BizDelayTaskMapper taskMapper;
    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private Long cabinetId;
    private String cabinetNo;
    private final Set<Long> customerIds = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(dataSource);
        cabinetNo = "CAB-P-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        TenantContext.runAs(TENANT, () -> {
            BizSite site = new BizSite();
            site.setSiteCode("P-" + cabinetNo);
            site.setName("轮询点位");
            site.setCategory("MALL");
            site.setStatus(1);
            siteMapper.insert(site);

            BizCabinet cabinet = new BizCabinet();
            cabinet.setSiteId(site.getId());
            cabinet.setCabinetNo(cabinetNo);
            cabinet.setName("轮询柜机");
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
        if (!customerIds.isEmpty()) {
            String in = String.join(",", customerIds.stream().map(id -> "?").toList());
            jdbc.update("delete from biz_point_txn where customer_id in (" + in + ")", customers);
            jdbc.update("delete from biz_point_account where customer_id in (" + in + ")", customers);
        }
        jdbc.update("delete from biz_deposit where order_id in (select id from biz_storage_order where cabinet_id = ?)", cabinetId);
        jdbc.update("delete from biz_storage_order where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_compartment where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_cabinet where id = ?", cabinetId);
        jdbc.update("delete from biz_site where tenant_id = ? and site_code = ?", TENANT, "P-" + cabinetNo);
        customerIds.clear();
        TenantContext.clear();
    }

    private BizStorageOrder fetch(String orderNo) {
        return TenantContext.callAs(TENANT, () -> orderMapper.selectOne(
                Wrappers.<BizStorageOrder>lambdaQuery().eq(BizStorageOrder::getOrderNo, orderNo)));
    }

    @Test
    @DisplayName("不显式调 runDue：后台线程自己把超时占位释放掉")
    void backgroundPollReleasesExpiredHold() {
        Long customerId = 985000L + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 9000);
        customerIds.add(customerId);
        TenantContext.runAs(TENANT, () -> points.recharge(customerId, 5000L,
                "CHG-" + UUID.randomUUID(), "轮询用例 funding"));
        String orderNo = TenantContext.callAs(TENANT, () -> orderService.create(customerId,
                new CreateOrderCommand(UUID.randomUUID().toString(), cabinetNo, "SMALL"))).orderNo();

        BizDelayTask planted = TenantContext.callAs(TENANT, () -> taskMapper.selectOne(
                Wrappers.<BizDelayTask>lambdaQuery()
                        .eq(BizDelayTask::getTaskType, TaskType.SLOT_RELEASE)
                        .eq(BizDelayTask::getBizKey, orderNo)));
        assertNotNull(planted, "下单应登记超时释放任务");

        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        OrderStatus status = OrderStatus.RESERVED;
        BizStorageOrder row = null;
        while (System.currentTimeMillis() < deadline) {
            row = fetch(orderNo);
            status = row.getStatus();
            if (status == OrderStatus.CANCELLED) {
                break;
            }
            try {
                Thread.sleep(200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        Long slotId = row.getSlotId();
        assertEquals(OrderStatus.CANCELLED, status,
                "后台轮询必须在 " + TIMEOUT_MS + "ms 内把这张超时单释放掉（没做到就是调度线程没跑通）");
        assertEquals(SlotStatus.FREE, TenantContext.callAs(TENANT,
                () -> slotMapper.selectById(slotId)).getStatus(), "格口要回到可分配");
        assertEquals(0L, TenantContext.callAs(TENANT, () -> points.accountOf(customerId)).getFrozenPoints(),
                "冻结必须随释放退回");

        BizDelayTask done = TenantContext.callAs(TENANT, () -> taskMapper.selectById(planted.getId()));
        assertEquals(TaskStatus.DONE, done.getStatus(), "任务本身要被后台收尾，不是只改了业务数据");
        assertTrue(done.getAttempt() >= 1, "attempt 要真实记录被抢过（租约条件更新生效的证据）");

        long latencyMs = java.time.Duration.between(done.getFireAt(), done.getUpdateTime()).toMillis();
        System.out.printf("MEASURED trigger=poll intervalMs=500 observedLatencyMs=%d%n", latencyMs);
        assertTrue(latencyMs < 5_000L,
                "poll-interval-ms=500 时实测延迟 " + latencyMs + "ms；上限给到 10 倍间隔而不是 2 倍，"
                        + "因为这一段里还有线程排队与单轮执行时长（实测均值已在 1.7s 量级）");
    }
}
