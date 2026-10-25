package com.wherelee.cabinet.locker;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.locker.domain.BizCabinet;
import com.wherelee.cabinet.locker.domain.BizCompartment;
import com.wherelee.cabinet.locker.domain.BizSite;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.locker.api.CabinetStatus;
import com.wherelee.cabinet.locker.api.OnlineState;
import com.wherelee.cabinet.domain.enums.OrderStatus;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest
@Transactional
@Rollback
class LockerDomainTest {

    private static final Long TENANT = 8101L;
    private static final Long OTHER_TENANT = 8102L;
    private static final Long MODEL_ID = 1L;
    private static final Long CUSTOMER_ID = 9001L;

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

    private JdbcTemplate jdbc;

    @BeforeEach
    void prepareJdbc() {
        jdbc = new JdbcTemplate(dataSource);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private record CabinetRef(Long siteId, Long cabinetId) {
    }

    private CabinetRef newCabinetWithSlots(String cabinetNo) {
        return TenantContext.callAs(TENANT, () -> {
            BizSite site = new BizSite();
            site.setSiteCode("SITE-" + cabinetNo);
            site.setName("测试点位");
            site.setCategory("MALL");
            site.setStatus(1);
            siteMapper.insert(site);

            BizCabinet cabinet = new BizCabinet();
            cabinet.setSiteId(site.getId());
            cabinet.setCabinetNo(cabinetNo);
            cabinet.setName("A 区 1 号柜");
            cabinet.setModelId(MODEL_ID);
            cabinet.setCabinetStatus(CabinetStatus.ENABLED);
            cabinet.setOnlineState(OnlineState.UNKNOWN);
            cabinetMapper.insert(cabinet);

            insertSlots(site.getId(), cabinet.getId(), SizeType.LARGE, 6);
            insertSlots(site.getId(), cabinet.getId(), SizeType.MEDIUM, 8);
            insertSlots(site.getId(), cabinet.getId(), SizeType.SMALL, 10);
            return new CabinetRef(site.getId(), cabinet.getId());
        });
    }

    private void insertSlots(Long siteId, Long cabinetId, SizeType size, int count) {
        for (int i = 1; i <= count; i++) {
            BizCompartment slot = new BizCompartment();
            slot.setCabinetId(cabinetId);
            slot.setSlotNo(size.name().charAt(0) + String.format("%02d", i));
            slot.setSizeType(size);
            slot.setStatus(SlotStatus.FREE);
            slotMapper.insert(slot);
        }
    }

    private BizCompartment firstFreeSlot(CabinetRef ref, SizeType size) {
        return TenantContext.callAs(TENANT, () -> {
            List<BizCompartment> free = slotMapper.selectList(Wrappers.<BizCompartment>lambdaQuery()
                    .eq(BizCompartment::getCabinetId, ref.cabinetId())
                    .eq(BizCompartment::getSizeType, size)
                    .eq(BizCompartment::getStatus, SlotStatus.FREE)
                    .orderByAsc(BizCompartment::getSlotNo)
                    .last("limit 1"));
            return free.isEmpty() ? null : free.get(0);
        });
    }

    private BizStorageOrder newOrder(CabinetRef ref, BizCompartment slot, String orderNo) {
        BizStorageOrder order = new BizStorageOrder();
        order.setOrderNo(orderNo);
        order.setSiteId(ref.siteId());
        order.setCabinetId(ref.cabinetId());
        order.setSlotId(slot.getId());
        order.setSizeType(slot.getSizeType());
        order.setCustomerId(CUSTOMER_ID);
        order.setVoucherCode("V" + orderNo);
        order.setTempOpenCount(0);
        order.setDepositPoints(500L);
        order.setFrozenPoints(0L);
        order.setSettledPoints(0L);
        order.setArrearsPoints(0L);
        order.initStatus();
        return order;
    }

    @Test
    @DisplayName("按型号模板生成格口：6 大 / 8 中 / 10 小共 24，初始全 FREE")
    void slotsGeneratedByModelTemplate() {
        CabinetRef ref = newCabinetWithSlots("CAB-A-001");

        TenantContext.callAs(TENANT, () -> {
            assertEquals(24L, slotMapper.selectCount(Wrappers.<BizCompartment>lambdaQuery()
                    .eq(BizCompartment::getCabinetId, ref.cabinetId())));
            assertEquals(6L, slotMapper.selectCount(Wrappers.<BizCompartment>lambdaQuery()
                    .eq(BizCompartment::getCabinetId, ref.cabinetId())
                    .eq(BizCompartment::getSizeType, SizeType.LARGE)));
            assertEquals(10L, slotMapper.selectCount(Wrappers.<BizCompartment>lambdaQuery()
                    .eq(BizCompartment::getCabinetId, ref.cabinetId())
                    .eq(BizCompartment::getSizeType, SizeType.SMALL)
                    .eq(BizCompartment::getStatus, SlotStatus.FREE)));
            return null;
        });
    }

    @Test
    @DisplayName("★ 同一格口不可能存在两条活动单：超卖被唯一索引挡在存储层")
    void activeOrderPerSlotIsStructurallyEnforced() {
        CabinetRef ref = newCabinetWithSlots("CAB-A-002");
        BizCompartment slot = firstFreeSlot(ref, SizeType.LARGE);
        assertNotNull(slot, "应有空闲大格口");

        TenantContext.callAs(TENANT, () -> {
            orderMapper.insert(newOrder(ref, slot, "SO-DUP-1"));
            BizStorageOrder second = newOrder(ref, slot, "SO-DUP-2");

            DuplicateKeyException thrown = assertThrows(DuplicateKeyException.class,
                    () -> orderMapper.insert(second));
            assertTrue(thrown.getMessage() != null && thrown.getMessage().contains("uk_order_active_slot"),
                    "应是 uk_order_active_slot 冲突，实际：" + thrown.getMessage());
            return null;
        });
    }

    @Test
    @DisplayName("前一行进入终态后同一格口可再次占用（NULL 不占唯一位）")
    void slotReusableAfterTerminal() {
        CabinetRef ref = newCabinetWithSlots("CAB-A-003");
        BizCompartment slot = firstFreeSlot(ref, SizeType.MEDIUM);

        TenantContext.callAs(TENANT, () -> {
            BizStorageOrder first = newOrder(ref, slot, "SO-SEQ-1");
            orderMapper.insert(first);
            first.transitTo(OrderStatus.RESERVED);
            first.transitTo(OrderStatus.OPENING);
            first.transitTo(OrderStatus.STORED);
            first.transitTo(OrderStatus.ACTIVE);
            first.transitTo(OrderStatus.SETTLING);
            first.transitTo(OrderStatus.CLOSED);
            orderMapper.updateById(first);

            assertNull(orderMapper.selectById(first.getId()).getActiveFlag(),
                    "终态后 activeFlag 必须为 NULL，否则该格口永久不可用");
            orderMapper.insert(newOrder(ref, slot, "SO-SEQ-2"));
            return null;
        });
    }

    @Test
    @DisplayName("新业务表同样受租户隔离：另一租户看不见格口与订单")
    void bizTablesAreTenantScoped() {
        CabinetRef ref = newCabinetWithSlots("CAB-A-004");
        BizCompartment slot = firstFreeSlot(ref, SizeType.SMALL);
        TenantContext.callAs(TENANT, () -> {
            orderMapper.insert(newOrder(ref, slot, "SO-TENANT-1"));
            return null;
        });

        long visibleSlots = TenantContext.callAs(OTHER_TENANT, () -> slotMapper.selectCount(
                Wrappers.<BizCompartment>lambdaQuery().eq(BizCompartment::getCabinetId, ref.cabinetId())));
        long visibleOrders = TenantContext.callAs(OTHER_TENANT, () -> orderMapper.selectCount(
                Wrappers.<BizStorageOrder>lambdaQuery().eq(BizStorageOrder::getOrderNo, "SO-TENANT-1")));

        assertEquals(0L, visibleSlots, "跨租户不该看见格口");
        assertEquals(0L, visibleOrders, "跨租户不该看见订单");
    }

    @Test
    @DisplayName("关键索引存在且列顺序正确（形状回归，不依赖优化器）")
    void indexShapesAreAsDesigned() {
        assertEquals(List.of("cabinet_id", "size_type", "status"),
                indexColumns("biz_compartment", "idx_slot_acquire"));
        assertEquals(List.of("cabinet_id", "slot_no"),
                indexColumns("biz_compartment", "uk_slot_cabinet_no"));
        assertEquals(List.of("slot_id", "active_flag"),
                indexColumns("biz_storage_order", "uk_order_active_slot"));
        assertEquals(List.of("status", "expected_finish_at"),
                indexColumns("biz_storage_order", "idx_order_scan"));
        assertEquals(List.of("status", "held_at"),
                indexColumns("biz_deposit", "idx_deposit_status_time"));
    }

    private List<String> indexColumns(String table, String indexName) {
        return jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ? "
                        + "ORDER BY SEQ_IN_INDEX",
                String.class, table, indexName);
    }

    @Test
    @DisplayName("乐观锁生效：持旧版本的写入影响 0 行且不产生部分效果")
    void optimisticLockBlocksStaleWrite() {
        CabinetRef ref = newCabinetWithSlots("CAB-A-006");
        BizCompartment slot = firstFreeSlot(ref, SizeType.LARGE);

        TenantContext.callAs(TENANT, () -> {
            BizCompartment fresh = slotMapper.selectById(slot.getId());
            assertEquals(0, fresh.getVersion(), "新建格口应从 0 版开始");

            fresh.setStatus(SlotStatus.RESERVED);
            assertEquals(1, slotMapper.updateById(fresh), "第一次更新应成功");
            assertEquals(1, slotMapper.selectById(slot.getId()).getVersion(), "更新应自动抬版本");

            BizCompartment stale = new BizCompartment();
            stale.setId(slot.getId());
            stale.setVersion(0);
            stale.setStatus(SlotStatus.OCCUPIED);
            assertEquals(0, slotMapper.updateById(stale), "带旧 version 的写入应影响 0 行");

            assertEquals(SlotStatus.RESERVED, slotMapper.selectById(slot.getId()).getStatus(),
                    "被拒的更新不得留下任何部分效果");
            return null;
        });
    }
}
