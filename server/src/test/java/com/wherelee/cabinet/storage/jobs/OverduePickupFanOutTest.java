package com.wherelee.cabinet.storage.jobs;

import com.wherelee.cabinet.domain.entity.BizDelayTask;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.OrderStatus;
import com.wherelee.cabinet.domain.enums.TaskType;
import com.wherelee.cabinet.storage.api.FundPort;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import com.wherelee.cabinet.locker.api.SizeType;
import com.wherelee.cabinet.servicedesk.api.WorkOrderConsolePort;
import com.wherelee.cabinet.servicedesk.api.WorkOrderIntakePort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OverduePickupFanOutTest {

    @Test
    @DisplayName("开单失败不能把任务收掉：必须返回 true 让下一轮重试")
    void fanOutFailureKeepsTheTaskAlive() {
        BizStorageOrderMapper orderMapper = mock(BizStorageOrderMapper.class);
        FundPort pricing = mock(FundPort.class);
        WorkOrderIntakePort intake = mock(WorkOrderIntakePort.class);
        WorkOrderConsolePort workOrders = mock(WorkOrderConsolePort.class);

        when(orderMapper.selectOne(any())).thenReturn(cappedOrder());
        when(orderMapper.updateById(any(BizStorageOrder.class))).thenReturn(1);
        when(pricing.isCapped(any(), anyLong())).thenReturn(true);
        when(intake.onOrderEvent(any(), any(), anyBoolean(), anyString(), anyString(), any()))
                .thenThrow(new RuntimeException("工单侧抖了一下"));

        assertTrue(handler(orderMapper, pricing, intake, workOrders).handle(task()),
                "开单没成必须继续排——返回 false 会让任务直接结束，这张工单永远补不回来");
        verify(intake).onOrderEvent(any(), any(), anyBoolean(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("开单成功才收掉任务：返回 false")
    void fanOutSuccessEndsTheTask() {
        BizStorageOrderMapper orderMapper = mock(BizStorageOrderMapper.class);
        FundPort pricing = mock(FundPort.class);
        WorkOrderIntakePort intake = mock(WorkOrderIntakePort.class);
        WorkOrderConsolePort workOrders = mock(WorkOrderConsolePort.class);

        when(orderMapper.selectOne(any())).thenReturn(cappedOrder());
        when(orderMapper.updateById(any(BizStorageOrder.class))).thenReturn(1);
        when(pricing.isCapped(any(), anyLong())).thenReturn(true);
        when(intake.onOrderEvent(any(), any(), anyBoolean(), anyString(), anyString(), any()))
                .thenReturn(55L);
        when(workOrders.openIdOf(any(), any())).thenReturn(55L);

        assertFalse(handler(orderMapper, pricing, intake, workOrders).handle(task()),
                "这单已经交出去了，继续每小时重进只会刷日志");
    }

    private OverduePickupHandler handler(BizStorageOrderMapper orderMapper, FundPort pricing,
                                         WorkOrderIntakePort intake, WorkOrderConsolePort workOrders) {
        OverduePickupHandler handler = new OverduePickupHandler(orderMapper, pricing,
                new SimpleMeterRegistry(), intake, workOrders);
        ReflectionTestUtils.setField(handler, "strandedDays", 7L);
        return handler;
    }

    private BizStorageOrder cappedOrder() {
        BizStorageOrder order = new BizStorageOrder();
        order.setId(1L);
        order.setOrderNo("SO-CAPPED");
        order.setTenantId(9L);
        order.setSizeType(SizeType.SMALL);
        order.setStatus(OrderStatus.ACTIVE);
        order.setPricingSnapshot("{}");
        order.setCreateTime(LocalDateTime.now().minusHours(50));
        order.setStartedAt(LocalDateTime.now().minusHours(50));
        order.setExpectedFinishAt(LocalDateTime.now().minusHours(2));
        return order;
    }

    private BizDelayTask task() {
        BizDelayTask task = new BizDelayTask();
        task.setId(2L);
        task.setTenantId(9L);
        task.setTaskType(TaskType.OVERDUE_PICKUP);
        task.setBizKey("SO-CAPPED");
        return task;
    }
}
