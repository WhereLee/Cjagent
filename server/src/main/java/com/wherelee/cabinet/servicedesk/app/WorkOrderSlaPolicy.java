package com.wherelee.cabinet.servicedesk.app;

import com.wherelee.cabinet.servicedesk.api.WorkOrderPriority;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * SLA 双时限：按优先级给“响应”和“解决”两个截止时间。
 *
 * <p>两个时限分开是因为它们回答的是不同问题：
 * <ul>
 *   <li>响应时限：多久之内必须有人开始处理（认领即视为响应）；</li>
 *   <li>解决时限：多久之内这件事必须有结论（修好、交接、或者给出可执行的安排）。</li>
 * </ul>
 *
 * <p>数值放在 {@code cabinet.work-order.sla}，改档位不需要发版；
 * 规则表里的分钟数非空时优先用规则的（按现象定制），为空就落到这里。
 */
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
