package com.wherelee.cabinet.domain.enums;

import java.util.Locale;

/**
 * 延迟任务类型。每个类型都对应一条"没有它就会留下永久现场"的具体事故。
 *
 * <ul>
 *   <li>{@link #SLOT_RELEASE}：占位/预扣超时未投件 —— 不释放，格口就永久不可用（第 9 刀的 NULL 语义只解决终态，
 *       不解决"用户走掉了"）；</li>
 *   <li>{@link #DOOR_NOT_CLOSED}：开柜后长时间未关门 —— 门磁状态未知，必须响；</li>
 *   <li>{@link #OVERDUE_PICKUP}：逾期未取 —— 计费封顶并把订单推到 EXPIRED（仍可取件，不是死端）；</li>
 *   <li>{@link #DEPOSIT_REFUND}：押金退还重试 —— 悬挂押金是不变式 4 要盯的；</li>
 *   <li>{@link #FREE_SET_SYNC}：Redis 空闲集合校准 —— 预扣的"少卖漂移"靠它收敛（第 10 刀承诺的账）；</li>
 *   <li>{@link #COMMAND_RESCAN}：设备指令无回执的回扫 —— 拆事务（等回执不持事务）留下的现场靠它收；</li>
 *   <li>{@link #COMMAND_SWEEP}：指令扫街保底 —— 下发与收敛之间进程被杀，逐条提醒盖不住；</li>
 *   <li>{@link #LEDGER_RECONCILE}：账平与状态一致性对账（只报告不自动改钱，改钱必须留人工痕迹）。</li>
 * </ul>
 *
 * <p><b>一切任务都是租户内的</b>：周期型任务（校准、对账）也按租户各登记一条（{@code bizKey = 租户号}）。
 * 曾考虑用 {@code tenant_id = 0} 做“平台级跳租户扫全表”，但那样 handler 必须手工绕过租户拦截器——
 * 一旦漏绕过，对账会因被过滤而“看起来完全正确”（实际只算了一个租户）。隔离默认成立比少写几行重要。
 */
public enum TaskType {

    SLOT_RELEASE,
    DOOR_NOT_CLOSED,
    OVERDUE_PICKUP,
    DEPOSIT_REFUND,
    COMMAND_RESCAN,
    COMMAND_SWEEP,
    FREE_SET_SYNC,
    LEDGER_RECONCILE;

    /**
     * 失败重试上限。给太大会把坏数据反复打爆日志，给太小会把瞬时故障判死；不同链路语义不同。
     */
    public int maxAttempts() {
        return switch (this) {
            case DEPOSIT_REFUND -> 8;      // 钱必须退出去，容忍更长的指数退避
            // 回扫自己已有重试预算（retry_count），任务层的次数只是“调度器自身出错”的上限，
            // 给大了会把一台挂死的柜机反复摇；这里给宽是因为回扫失败通常是抛异常，而不是没回执
            case COMMAND_RESCAN -> 6;
            case FREE_SET_SYNC, LEDGER_RECONCILE, COMMAND_SWEEP -> 3;
            default -> 5;
        };
    }

    /** handler 干完后 worker 要不要再排下一轮（周期型与跟踪型才要）。 */
    public boolean recurring() {
        return this == OVERDUE_PICKUP || this == FREE_SET_SYNC || this == LEDGER_RECONCILE
                || this == COMMAND_SWEEP || this == COMMAND_RESCAN;
    }

    public static TaskType of(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
