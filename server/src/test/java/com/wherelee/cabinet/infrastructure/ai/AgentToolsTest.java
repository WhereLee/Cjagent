package com.wherelee.cabinet.infrastructure.ai;

import com.wherelee.cabinet.application.storage.StorageOrderFacade;
import com.wherelee.cabinet.application.storage.StorageQueryService;
import com.wherelee.cabinet.application.storage.dto.StorageOrderView;
import com.wherelee.cabinet.application.support.SupportAgentPort.SupportSession;
import com.wherelee.cabinet.application.workorder.WorkOrderIntake;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.entity.BizWorkOrder;
import com.wherelee.cabinet.domain.enums.CommandAction;
import com.wherelee.cabinet.domain.enums.WorkOrderCategory;
import com.wherelee.cabinet.domain.enums.WorkOrderIntent;
import com.wherelee.cabinet.domain.enums.WorkOrderKind;
import com.wherelee.cabinet.domain.enums.WorkOrderPriority;
import com.wherelee.cabinet.domain.enums.WorkOrderSource;
import com.wherelee.cabinet.domain.enums.WorkOrderStatus;
import com.wherelee.cabinet.domain.enums.WorkOrderType;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizWorkOrderMapper;
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
import static org.mockito.ArgumentMatchers.isNull;
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
    private BizWorkOrderMapper workOrderMapper;

    @BeforeEach
    void setUp() {
        queries = mock(StorageQueryService.class);
        doors = mock(StorageOrderFacade.class);
        intake = mock(WorkOrderIntake.class);
        orderMapper = mock(BizStorageOrderMapper.class);
        workOrderMapper = mock(BizWorkOrderMapper.class);
    }

    private AgentTools tools(String orderNo) {
        return new AgentTools(queries, doors, intake, orderMapper, workOrderMapper,
                new SupportSession(CUSTOMER, orderNo, "帮我看看"));
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
    @DisplayName("设备故障必须先尝试：没调用 retryMyDoor 的转报被拒绝")
    void deviceFaultRequiresAttempt() {
        BizStorageOrder own = new BizStorageOrder();
        own.setId(9L);
        own.setOrderNo(ORDER_NO);
        own.setCustomerId(CUSTOMER);
        when(orderMapper.selectOne(any())).thenReturn(own);

        String out = tools(ORDER_NO).raiseTicket("DEVICE_FAULT", "门打不开");

        assertTrue(out.contains("先调用 retryMyDoor"), out);
        verifyNoInteractions(intake);
    }

    @Test
    @DisplayName("重开返回真实回执：已下发无回执原样转述，不冒充已打开")
    void retryDoorRelaysDeviceMessage() {
        when(doors.openDoor(eq(CUSTOMER), eq(ORDER_NO), eq(CommandAction.OPEN_TEMP)))
                .thenReturn(new StorageOrderView(ORDER_NO, "CAB-1", "A-01", "SMALL", "ACTIVE",
                        240, 0, "cas", "指令已下发，但未收到柜机明确回执"));

        String out = tools(ORDER_NO).retryMyDoor();

        assertTrue(out.contains("已下发") && out.contains("未收到柜机明确回执"), out);
    }

    @Test
    @DisplayName("建单类型白名单：尝试过才能建，合法类型走统一入口并回填工单号")
    void raiseTicketMapsWhitelistedKinds() {
        BizStorageOrder own = new BizStorageOrder();
        own.setId(9L);
        own.setOrderNo(ORDER_NO);
        own.setCustomerId(CUSTOMER);
        when(orderMapper.selectOne(any())).thenReturn(own);
        when(doors.openDoor(eq(CUSTOMER), eq(ORDER_NO), eq(CommandAction.OPEN_TEMP)))
                .thenReturn(new StorageOrderView(ORDER_NO, "CAB-1", "A-01", "SMALL", "ACTIVE",
                        240, 0, "cas", "指令已下发，但未收到柜机明确回执"));
        when(intake.onOrderEvent(same(own), eq(WorkOrderType.DEVICE_FAULT), eq(false),
                contains("AI 客服转报"), anyString(), any(), eq(WorkOrderKind.REQUEST),
                eq(WorkOrderSource.AI), eq(WorkOrderIntent.DOOR_NOT_OPEN),
                eq(WorkOrderCategory.DOOR_FAULT), isNull(), contains("OPEN_TEMP"), anyString()))
                .thenReturn(555L);

        AgentTools agent = tools(ORDER_NO);
        agent.retryMyDoor();
        String out = agent.raiseTicket("DEVICE_FAULT", "用户报取件时门打不开",
                "DOOR_NOT_OPEN", "OPEN_TEMP 已下发无回执");

        assertTrue(out.contains("555"), out);
        assertTrue(agent.escalated() && "555".equals(agent.ticketNo()));

        String illegal = tools(ORDER_NO).raiseTicket("ITEM_LEFT", "不该由客服建的类型");
        assertTrue(illegal.contains("只支持"), illegal);
        verify(intake, times(1)).onOrderEvent(any(), any(), anyBoolean(), anyString(), anyString(),
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("工单进度只读：返回状态与标题，不泄露内部备注")
    void ticketStatusOmitsInternalNote() {
        BizWorkOrder order = new BizWorkOrder();
        order.setId(42L);
        order.setKind(WorkOrderKind.REQUEST);
        order.setStatus(WorkOrderStatus.PENDING);
        order.setPriority(WorkOrderPriority.P2);
        order.setResolveDueAt(LocalDateTime.now().plusHours(2));
        order.setTitle("格口 A-01 门打不开");
        order.setResultNote("内部备注：不要给用户看");
        when(workOrderMapper.selectList(any())).thenReturn(List.of(order));

        String out = tools(ORDER_NO).myTicketStatus();

        assertTrue(out.contains("42") && out.contains("P2") && out.contains("门打不开"), out);
        assertFalse(out.contains("内部备注"), "工单内部备注不能进模型上下文：" + out);
    }

    @Test
    @DisplayName("未升级时 escalated 为 false：AgentReply 的语义不能被工具误报")
    void noTicketMeansNotEscalated() {
        assertFalse(tools(ORDER_NO).escalated());
    }
}
