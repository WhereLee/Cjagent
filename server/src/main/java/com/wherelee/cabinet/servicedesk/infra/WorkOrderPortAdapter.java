package com.wherelee.cabinet.servicedesk.infra;

import com.wherelee.cabinet.locker.api.CompartmentAnomaly;
import com.wherelee.cabinet.servicedesk.api.WorkOrderPort;
import com.wherelee.cabinet.servicedesk.api.WorkOrderType;
import com.wherelee.cabinet.servicedesk.app.WorkOrderIntake;
import com.wherelee.cabinet.servicedesk.app.WorkOrderService;
import org.springframework.stereotype.Component;

/**
 * 工单端口适配器：把格口状态机要的那几个动作翻译成工单服务的方法。
 *
 * <p>为什么不直接让格口状态机注入 WorkOrderService：那个类 58KB、方法几十个，
 * 一旦交出去，“顺手在这里改个工单字段”这种事迟早会发生。适配器把可见面压到七个动作，
 * 也把“格口用到工单”这件事写在了明面上。
 */
@Component
public class WorkOrderPortAdapter implements WorkOrderPort {

    private final WorkOrderIntake intake;
    private final WorkOrderService workOrders;

    public WorkOrderPortAdapter(WorkOrderIntake intake, WorkOrderService workOrders) {
        this.intake = intake;
        this.workOrders = workOrders;
    }

    @Override
    public void openForAnomaly(Long slotId, CompartmentAnomaly anomaly, String reason) {
        intake.onAnomaly(slotId, anomaly, reason);
    }

    @Override
    public int remindedTimes(Long slotId, WorkOrderType type) {
        return workOrders.remindedTimes(slotId, type);
    }

    @Override
    public Long openIdOf(Long slotId, WorkOrderType type) {
        return workOrders.openIdOf(slotId, type);
    }

    @Override
    public void remind(Long slotId, WorkOrderType type, String message) {
        workOrders.remind(slotId, type, message);
    }

    @Override
    public void escalate(Long slotId, WorkOrderType type, String reason) {
        workOrders.escalate(slotId, type, reason);
    }

    @Override
    public void noteStep(Long slotId, WorkOrderType type, String step, String note) {
        workOrders.noteStep(slotId, type, step, note);
    }

    @Override
    public void autoClose(Long slotId, WorkOrderType type) {
        workOrders.autoClose(slotId, type);
    }
}
