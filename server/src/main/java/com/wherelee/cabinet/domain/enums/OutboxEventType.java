package com.wherelee.cabinet.domain.enums;

/**
 * outbox 里记的是哪一类事实。
 *
 * <p>新增一类事实就要同时补两处：入队口的 append 方法、中继里的 dispatch 分支。
 * 不加新表、不加新调度——这也是选独立 outbox 表而不是复用 {@code biz_delay_task} 之后
 * 必须付的代价（那张表靠 {@code task_type} 就能加新类型，这张表靠枚举）。
 */
public enum OutboxEventType {

    /** 格口异常落定后要开的工单（载荷：slotId / anomaly / reason）。 */
    WORK_ORDER_ANOMALY,

    /** 遗留物归因到终态单后要通知物主（载荷：channel / kind / dedupKey / content / customerId / workOrderId / orderNo）。 */
    OWNER_NOTICE
}
