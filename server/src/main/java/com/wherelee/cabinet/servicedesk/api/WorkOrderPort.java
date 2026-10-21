package com.wherelee.cabinet.servicedesk.api;

import com.wherelee.cabinet.locker.api.CompartmentAnomaly;

/**
 * 工单端口：别的服务只需要“因异常开单、催一催、收一收”，不需要知道工单内部怎么流转。
 *
 * <p>为什么按动作而不是按实体：格口状态机在标异常的那一刻要同时开单、在门关上时
 * 要自动收单、在提醒阶梯里要催办。这些动作的名字就摆在门态设计文档里，直接暴露它们
 * 比把 WorkOrderService 交出去更安全——那个类有 58KB，交出去就再也没有边界了。
 */
public interface WorkOrderPort {

    /** 设备异常 → 开单（按异常类型映射工单类型；幂等，重复标同一种异常不刷多张）。 */
    void openForAnomaly(Long slotId, CompartmentAnomaly anomaly, String reason);

    /** 门未关提醒阶梯的第几次：次数就是状态，不另存台阶字段。 */
    int remindedTimes(Long slotId, WorkOrderType type);

    /** 按格口找一张未关闭的某类工单（找不到返回 null）。 */
    Long openIdOf(Long slotId, WorkOrderType type);

    void remind(Long slotId, WorkOrderType type, String message);

    void escalate(Long slotId, WorkOrderType type, String reason);

    void noteStep(Long slotId, WorkOrderType type, String step, String note);

    /** 事实自己消失了：转 AUTO_CLOSED 留底，不删除、不记成结论。 */
    void autoClose(Long slotId, WorkOrderType type);
}
