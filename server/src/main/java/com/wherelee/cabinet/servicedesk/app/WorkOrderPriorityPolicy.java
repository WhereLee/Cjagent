package com.wherelee.cabinet.servicedesk.app;

import com.wherelee.cabinet.servicedesk.api.WorkOrderImpact;
import com.wherelee.cabinet.servicedesk.api.WorkOrderPriority;
import com.wherelee.cabinet.servicedesk.api.WorkOrderUrgency;
import org.springframework.stereotype.Component;

@Component
public class WorkOrderPriorityPolicy {

    public WorkOrderPriority of(WorkOrderImpact impact, WorkOrderUrgency urgency) {
        WorkOrderUrgency actual = urgency == null ? WorkOrderUrgency.NORMAL : urgency;
        if (actual == WorkOrderUrgency.LOW) {
            return WorkOrderPriority.P4;
        }
        boolean broad = impact == WorkOrderImpact.CABINET || impact == WorkOrderImpact.ACCOUNT;
        if (actual == WorkOrderUrgency.HIGH) {
            return broad ? WorkOrderPriority.P1 : WorkOrderPriority.P2;
        }
        return broad ? WorkOrderPriority.P2 : WorkOrderPriority.P3;
    }
}
