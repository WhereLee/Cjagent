package com.wherelee.cabinet.domain.enums;

/**
 * outbox 行的投递状态。
 *
 * <p>没有 RUNNING：投递是幂等的（工单靠唯一索引、消息靠 dedup_key），
 * 不需要"执行中"这个状态来表达互斥——把 {@code next_attempt_at} 推远就足以让并发实例拿不到同一条。
 * 少一个状态就少一处"进程被 kill 后卡在 RUNNING"的收尾逻辑。
 */
public enum OutboxStatus {

    /** 待投递。 */
    PENDING,

    /** 投递成功，终态。 */
    DONE,

    /** 投递失败，等退避时间到再试。 */
    FAILED,

    /** 重试次数用尽，等人工。 */
    DEAD
}
