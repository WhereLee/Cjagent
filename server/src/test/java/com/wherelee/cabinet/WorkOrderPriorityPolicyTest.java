package com.wherelee.cabinet;

import com.wherelee.cabinet.servicedesk.app.WorkOrderPriorityPolicy;
import com.wherelee.cabinet.servicedesk.api.WorkOrderImpact;
import com.wherelee.cabinet.servicedesk.api.WorkOrderPriority;
import com.wherelee.cabinet.servicedesk.api.WorkOrderUrgency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 优先级矩阵：影响 × 紧急度 → P1-P4，纯规则，不起容器。 */
@DisplayName("工单优先级矩阵：影响大、时间急的先办")
class WorkOrderPriorityPolicyTest {

    private final WorkOrderPriorityPolicy policy = new WorkOrderPriorityPolicy();

    @Test
    @DisplayName("整柜/账户级 + 高紧急 = P1")
    void broadAndUrgentIsP1() {
        assertEquals(WorkOrderPriority.P1, policy.of(WorkOrderImpact.CABINET, WorkOrderUrgency.HIGH));
        assertEquals(WorkOrderPriority.P1, policy.of(WorkOrderImpact.ACCOUNT, WorkOrderUrgency.HIGH));
    }

    @Test
    @DisplayName("单格 + 高紧急 = P2；整柜 + 普通 = P2")
    void singleUrgentAndBroadNormalAreP2() {
        assertEquals(WorkOrderPriority.P2, policy.of(WorkOrderImpact.SINGLE, WorkOrderUrgency.HIGH));
        assertEquals(WorkOrderPriority.P2, policy.of(WorkOrderImpact.CABINET, WorkOrderUrgency.NORMAL));
        assertEquals(WorkOrderPriority.P2, policy.of(WorkOrderImpact.ACCOUNT, WorkOrderUrgency.NORMAL));
    }

    @Test
    @DisplayName("单格 + 普通 = P3；低紧急一律 P4；空值按普通处理")
    void singleNormalIsP3AndLowIsP4() {
        assertEquals(WorkOrderPriority.P3, policy.of(WorkOrderImpact.SINGLE, WorkOrderUrgency.NORMAL));
        assertEquals(WorkOrderPriority.P4, policy.of(WorkOrderImpact.SINGLE, WorkOrderUrgency.LOW));
        assertEquals(WorkOrderPriority.P4, policy.of(WorkOrderImpact.CABINET, WorkOrderUrgency.LOW));
        assertEquals(WorkOrderPriority.P3, policy.of(null, null));
    }
}
