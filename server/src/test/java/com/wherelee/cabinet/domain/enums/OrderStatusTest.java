package com.wherelee.cabinet.domain.enums;

import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderStatusTest {

    @Test
    @DisplayName("终态没有出边：已关闭/已取消的单不能被任何任务改回活动中")
    void terminalStatesHaveNoExit() {
        for (OrderStatus terminal : EnumSet.of(OrderStatus.CLOSED, OrderStatus.CANCELLED)) {
            for (OrderStatus target : OrderStatus.values()) {
                assertFalse(terminal.canTransitTo(target), terminal + " 不该能迁移到 " + target);
            }
        }
    }

    @Test
    @DisplayName("每个状态都至少有一个出口（终态除外）：异常状态必须有走出去的路")
    void abnormalAndExpiredAreNotDeadEnds() {
        // ABNORMAL/EXPIRED 若变成死端，就会出现"行李永久锁在柜子里"——这是本域最严重的失败模式
        assertTrue(OrderStatus.ABNORMAL.canTransitTo(OrderStatus.CLOSED));
        assertTrue(OrderStatus.ABNORMAL.canTransitTo(OrderStatus.CANCELLED));
        assertTrue(OrderStatus.ABNORMAL.canTransitTo(OrderStatus.ACTIVE));
        assertTrue(OrderStatus.EXPIRED.canTransitTo(OrderStatus.SETTLING));

        for (OrderStatus status : OrderStatus.values()) {
            boolean hasExit = EnumSet.allOf(OrderStatus.class).stream().anyMatch(status::canTransitTo);
            assertEquals(status.isTerminal(), !hasExit, status + " 的出边与其终态标记应一致");
        }
    }

    @Test
    @DisplayName("activeFlag 与终态严格同步：这是唯一索引防超卖的语义基础")
    void activeFlagMatchesTerminality() {
        for (OrderStatus status : OrderStatus.values()) {
            if (status.isTerminal()) {
                assertNull(status.activeFlag(), status + " 是终态，activeFlag 必须为 NULL");
            } else {
                assertEquals(1, status.activeFlag(), status + " 是活动态，activeFlag 必须为 1");
            }
        }
    }

    @Test
    @DisplayName("非法跳步被拒：不能从 RESERVED 直接跳到 SETTLING")
    void illegalJumpRejected() {
        assertFalse(OrderStatus.RESERVED.canTransitTo(OrderStatus.SETTLING));
        assertFalse(OrderStatus.INIT.canTransitTo(OrderStatus.ACTIVE));
    }

    @Test
    @DisplayName("订单实体只能经 transitTo 改状态，且改状态一定带上 activeFlag")
    void entityTransitKeepsInvariant() {
        BizStorageOrder order = new BizStorageOrder();
        order.setOrderNo("SO-TEST-1");
        order.initStatus();
        assertEquals(OrderStatus.INIT, order.getStatus());
        assertEquals(1, order.getActiveFlag());

        // 非法迁移：直接抛业务异常，且不留半成品状态
        assertThrows(BizException.class, () -> order.transitTo(OrderStatus.SETTLING));
        assertEquals(OrderStatus.INIT, order.getStatus());
        assertEquals(1, order.getActiveFlag());

        order.transitTo(OrderStatus.RESERVED);
        order.transitTo(OrderStatus.OPENING);
        order.transitTo(OrderStatus.STORED);
        order.transitTo(OrderStatus.ACTIVE);
        order.transitTo(OrderStatus.SETTLING);
        order.transitTo(OrderStatus.CLOSED);
        assertEquals(OrderStatus.CLOSED, order.getStatus());
        assertNull(order.getActiveFlag(), "进入终态后必须放开唯一索引占位，否则该格口永久不可用");
    }

    @Test
    @DisplayName("尺寸适配：大格口能装大/中/小需求，小格口只能装小需求")
    void sizeFitsMatrix() {
        assertTrue(SizeType.LARGE.fits(SizeType.LARGE));
        assertTrue(SizeType.LARGE.fits(SizeType.SMALL));
        assertFalse(SizeType.SMALL.fits(SizeType.LARGE));
        assertFalse(SizeType.MEDIUM.fits(SizeType.LARGE));
    }
}
