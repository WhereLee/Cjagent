package com.wherelee.cabinet.servicedesk.api;

import java.util.Locale;
import java.util.Set;

/**
 * 工单状态（19B）。
 *
 * <p>关键区分是 <b>RESOLVED 与 AUTO_CLOSED</b>：用户明确要求“10 分钟内门关上了，工单自动转化，
 * 不意味着删除，需要留底”。如果把自动关闭也写成 RESOLVED，就分不清“人处理过”与“事实自己消失了”，
 * 而这两件事在事后追责与误报统计里完全不同。
 */
public enum WorkOrderStatus {

    /** 已开单，还没人认领 */
    PENDING,
    /** 已被某个具体处理人认领（同一张单只可能有一人认领成功） */
    CLAIMED,
    /** 人已处理并给出结论（含“判断为可不处理”） */
    RESOLVED,
    /** 事实自行消失（门关上了/遗留被取走）而自动转化留底，不是人工结论 */
    AUTO_CLOSED,
    /** 重复工单作废（唯一索引冲突后的合并处置） */
    CANCELLED;

    /** 仍占用“未关闭唯一索引”槽位的状态：只有这两种算开着。 */
    private static final Set<WorkOrderStatus> OPEN = Set.of(PENDING, CLAIMED);

    public boolean isOpen() {
        return OPEN.contains(this);
    }

    /** 关闭时必须把 active_flag 置 NULL，否则这一格的同类异常永远开不出新单。 */
    public boolean closes() {
        return this == RESOLVED || this == AUTO_CLOSED || this == CANCELLED;
    }

    public static WorkOrderStatus of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
