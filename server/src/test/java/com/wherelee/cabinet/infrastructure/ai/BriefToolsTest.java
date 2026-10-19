package com.wherelee.cabinet.infrastructure.ai;

import com.wherelee.cabinet.application.workorder.WorkOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 简报工具集的安全语义（第 23 刀）：归纳 agent 的三条硬边界用代码穷举——
 * <b>全只读</b>（没有 resolve/claim/notePhoneCall 可调）、
 * <b>电话不进模型</b>（View 里有也不输出）、
 * <b>证据照片引用不出系统</b>（本地路径对模型无意义）。
 */
class BriefToolsTest {

    private static final Long WO = 501L;

    private WorkOrderService workOrders;

    @BeforeEach
    void setUp() {
        workOrders = mock(WorkOrderService.class);
    }

    private BriefTools tools() {
        return new BriefTools(workOrders, WO);
    }

    private WorkOrderService.View view() {
        LocalDateTime now = LocalDateTime.now();
        return new WorkOrderService.View(WO, "INCIDENT", "DEVICE", "DEVICE_FAULT", null, "DOOR_FAULT",
                "P1", "SINGLE", "HIGH", false, "PENDING", "ONLINE", "ONLINE",
                "A-01", "CAB-1", "测试点位", "SO-1", "13900001234",
                "格口 A-01 门未关：取件后未回关", "门=开着；柜内=有物；异常=DOOR_OPEN",
                null, null, null, null, null, now, now, now, null, null, now, null, 0);
    }

    @Test
    @DisplayName("工单上下文里没有客户电话：View 带了电话，工具输出也不出现")
    void contextOmitsCustomerPhone() {
        when(workOrders.one(WO)).thenReturn(view());

        String out = tools().workOrderContext();

        assertTrue(out.contains("DEVICE_FAULT") && out.contains("A-01"), out);
        assertFalse(out.contains("13900001234"), "电话绝不能进模型上下文：" + out);
    }

    @Test
    @DisplayName("证据输出不含照片引用：本地路径对模型无意义，不属于事实")
    void evidenceOmitsPhotoRef() {
        when(workOrders.evidenceFor(WO)).thenReturn(List.of(
                new WorkOrderService.EvidenceView("OPEN_DOOR", "SENSOR", "UNKNOWN",
                        new BigDecimal("0.42"), "未识别", "/data/photo/1.jpg",
                        "开门采集", LocalDateTime.now())));

        String out = tools().evidenceTimeline();

        assertTrue(out.contains("UNKNOWN") && out.contains("0.42"), out);
        assertFalse(out.contains("/data/photo/1.jpg"), "照片引用不能进模型上下文：" + out);
    }

    @Test
    @DisplayName("相似已结单：有历史给原文，没历史明说暂无")
    void similarResolvedEmptyAndPopulated() {
        when(workOrders.similarResolved(WO, 3)).thenReturn(List.of());
        assertTrue(tools().similarResolved().contains("暂无"), "没有历史要明说，不许编");

        when(workOrders.similarResolved(WO, 3)).thenReturn(List.of(
                new com.wherelee.cabinet.application.workorder.WorkOrderBriefPort.SimilarCase(
                        "格口 A-01 门未关：取件后未回关", "电话联系后用户返回关好柜门，柜内无物", "2026-10-10")));
        String out = tools().similarResolved();
        assertTrue(out.contains("电话联系后用户返回关好柜门"), "结论必须原文转述：" + out);
    }

    @Test
    @DisplayName("四个工具全只读：写方法一次都不许被调")
    void toolsAreReadOnly() {
        when(workOrders.one(WO)).thenReturn(view());
        when(workOrders.timeline(WO)).thenReturn(List.of(
                new WorkOrderService.EventView("CREATED", "SYSTEM", null, null,
                        "格口 A-01 门未关：取件后未回关", LocalDateTime.now())));
        when(workOrders.evidenceFor(WO)).thenReturn(List.of());
        when(workOrders.similarResolved(WO, 3)).thenReturn(List.of());

        tools().workOrderContext();
        tools().eventTimeline();
        tools().evidenceTimeline();
        tools().similarResolved();

        assertTrue(tools().eventTimeline().contains("CREATED"), "流水要带上事件类型");

        // 只读是硬边界：任何写方法被调都算破坏（自动关单/认领/记录电话在代码层面就不可能）
        verify(workOrders, never()).resolve(anyLong(), anyLong(), anyString(), anyBoolean());
        verify(workOrders, never()).claim(anyLong(), anyLong());
        verify(workOrders, never()).notePhoneCall(anyLong(), anyLong(), anyString());
        verify(workOrders, never()).open(any(WorkOrderService.NewOrder.class));
        verify(workOrders, never()).open(any(WorkOrderService.NewOrderV2.class));
    }
}
