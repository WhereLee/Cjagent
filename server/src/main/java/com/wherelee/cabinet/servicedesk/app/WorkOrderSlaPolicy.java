package com.wherelee.cabinet.servicedesk.app;

import com.wherelee.cabinet.servicedesk.api.WorkOrderPriority;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WorkOrderSlaPolicy {

    @Value("${cabinet.work-order.sla.P1.response-minutes:10}")
    private int p1ResponseMinutes;

    @Value("${cabinet.work-order.sla.P1.resolve-minutes:120}")
    private int p1ResolveMinutes;

    @Value("${cabinet.work-order.sla.P2.response-minutes:30}")
    private int p2ResponseMinutes;

    @Value("${cabinet.work-order.sla.P2.resolve-minutes:480}")
    private int p2ResolveMinutes;

    @Value("${cabinet.work-order.sla.P3.response-minutes:120}")
    private int p3ResponseMinutes;

    @Value("${cabinet.work-order.sla.P3.resolve-minutes:1440}")
    private int p3ResolveMinutes;

    @Value("${cabinet.work-order.sla.P4.response-minutes:480}")
    private int p4ResponseMinutes;

    @Value("${cabinet.work-order.sla.P4.resolve-minutes:4320}")
    private int p4ResolveMinutes;

    public int responseMinutes(WorkOrderPriority priority) {
        return switch (priority == null ? WorkOrderPriority.P3 : priority) {
            case P1 -> p1ResponseMinutes;
            case P2 -> p2ResponseMinutes;
            case P3 -> p3ResponseMinutes;
            case P4 -> p4ResponseMinutes;
        };
    }

    public int resolveMinutes(WorkOrderPriority priority) {
        return switch (priority == null ? WorkOrderPriority.P3 : priority) {
            case P1 -> p1ResolveMinutes;
            case P2 -> p2ResolveMinutes;
            case P3 -> p3ResolveMinutes;
            case P4 -> p4ResolveMinutes;
        };
    }
}
