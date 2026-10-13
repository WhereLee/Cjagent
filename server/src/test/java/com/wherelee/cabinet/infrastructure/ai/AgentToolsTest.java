package com.wherelee.cabinet.infrastructure.ai;

import com.wherelee.cabinet.application.storage.StorageOrderFacade;
import com.wherelee.cabinet.application.storage.StorageQueryService;
import com.wherelee.cabinet.application.support.SupportAgentPort.SupportSession;
import com.wherelee.cabinet.application.workorder.WorkOrderIntake;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.CommandAction;
import com.wherelee.cabinet.domain.enums.WorkOrderType;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 客服工具集的安全语义（第 22 刀）：这三条是 agent 不得越过的线，
 * 用穷举而不是靠模型自觉——
 * <b>身份只在构造时注入</b>（模型没有任何身份参数可伪造）、
 * <b>重开只试一次</b>（失败不重复摇设备）、
 * <b>建单前归属校验</b>（别人的单一律"未找到"）。
 */
class AgentToolsTest {

    private static final Long CUSTOMER = 77L;
    private static final String ORDER_NO = "SP-IT-1";

    private StorageQueryService queries;
    private StorageOrderFacade doors;
    private WorkOrderIntake intake;
    private BizStorageOrderMapper orderMapper;

    @BeforeEach
    void setUp() {
        queries = mock(StorageQueryService.class);
        doors = mock(StorageOrderFacade.class);
        intake = mock(WorkOrderIntake.class);
        orderMapper = mock(BizStorageOrderMapper.class);
    }

    private AgentTools tools(String orderNo) {
        return new AgentTools(queries, doors, intake, orderMapper, new SupportSession(CUSTOMER, orderNo, "帮我看看"));
    }

    private StorageQueryService.OrderView view(String orderNo) {
        return new StorageQueryService.OrderView(orderNo, "CAB-1", "A-01", "SMALL", "ACTIVE",
                LocalDateTime.now(), LocalDateTime.now().plusHours(4), LocalDateTime.now(),
                0L, 0L, false);
    }

    @Test
    @DisplayName("查单只用登录用户的身份：查询参数里的 customerId 来自构造，与模型无关")
    void overviewUsesOwnCustomer() {
        when(queries.myOrders(CUSTOMER, 10)).thenReturn(List.of(view(ORDER_NO)));

        String out = tools(ORDER_NO).myOrderOverview();

        assertTrue(out.contains(ORDER_NO), out);
        assertTrue(out.contains("[本次相关]"), "关联订单要有标记：" + out);
        verify(queries).myOrders(CUSTOMER, 10);
    }

    @Test
    @DisplayName("没关联订单就不开门：重开工具在源头拒绝，不碰设备")
    void retryDoorRequiresLinkedOrder() {
        String out = tools(null).retryMyDoor();

        assertTrue(out.contains("没有关联"), out);
        verifyNoInteractions(doors);
    }

    @Test
    @DisplayName("重开只试一次：第一次失败后，第二次调用直接拒绝且不再下发指令")
    void retryDoorGivesUpAfterOneAttempt() {
        when(doors.openDoor(eq(CUSTOMER), eq(ORDER_NO), eq(CommandAction.OPEN_TEMP)))
                .thenThrow(new BizException("柜机不在线，指令下发失败"));

        AgentTools agent = tools(ORDER_NO);
        String first = agent.retryMyDoor();
        assertTrue(first.contains("开门失败") && first.contains("柜机不在线"), first);

        String second = agent.retryMyDoor();
        assertTrue(second.contains("不能重复尝试"), second);
        verify(doors, times(1)).openDoor(any(), anyString(), any());
    }

    @Test
    @DisplayName("建单前归属校验：查到的单不是本人的，一律“未找到”且不开单")
    void raiseTicketRejectsForeignOrder() {
        BizStorageOrder foreign = new BizStorageOrder();
        foreign.setId(1L);
        foreign.setOrderNo(ORDER_NO);
        foreign.setCustomerId(999L);
        when(orderMapper.selectOne(any())).thenReturn(foreign);

        String out = tools(ORDER_NO).raiseTicket("DEVICE_FAULT", "门打不开");

        assertTrue(out.contains("未找到"), out);
        verifyNoInteractions(intake);
    }

    @Test
    @DisplayName("建单类型白名单：两种可建，其余拒绝；合法的走统一入口并回填工单号")
    void raiseTicketMapsWhitelistedKinds() {
        BizStorageOrder own = new BizStorageOrder();
        own.setId(9L);
        own.setOrderNo(ORDER_NO);
        own.setCustomerId(CUSTOMER);
        when(orderMapper.selectOne(any())).thenReturn(own);
        when(intake.onOrderEvent(same(own), eq(WorkOrderType.DEVICE_FAULT), eq(false),
                contains("AI 客服转报"), anyString(), any())).thenReturn(555L);

        AgentTools agent = tools(ORDER_NO);
        String out = agent.raiseTicket("DEVICE_FAULT", "用户报取件时门打不开；已远程重开一次，无回执");

        assertTrue(out.contains("555"), out);
        assertTrue(agent.escalated() && "555".equals(agent.ticketNo()));

        String illegal = tools(ORDER_NO).raiseTicket("ITEM_LEFT", "不该由客服建的类型");
        assertTrue(illegal.contains("只支持"), illegal);
        verify(intake, times(1)).onOrderEvent(any(), any(), anyBoolean(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("未升级时 escalated 为 false：AgentReply 的语义不能被工具误报")
    void noTicketMeansNotEscalated() {
        assertFalse(tools(ORDER_NO).escalated());
    }
}
