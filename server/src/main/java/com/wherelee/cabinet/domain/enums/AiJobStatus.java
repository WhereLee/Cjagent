package com.wherelee.cabinet.domain.enums;

/**
 * AI 作业的状态。
 *
 * <p>比 outbox 多一个 {@link #RUNNING}：生成一段几千字的报告要十几秒，
 * 期间前端轮询必须能区分“排着队”和“正在写”。outbox 的投递是幂等的、瞬时完成的，
 * 所以那边故意没有这个状态。
 *
 * <p>没有 DEAD：作业失败到次数用尽就是 {@link #FAILED}——它是终态且对用户可见（“这次没成，再来一次”）。
 * 与延迟任务的 DEAD（等人工）不同：这里重跑一次就是用户点一下，不需要人工介入。
 */
public enum AiJobStatus {

    /** 已登记，等待执行。 */
    SUBMITTED,

    /** 正在调模型。租约到期可被其它实例接管。 */
    RUNNING,

    /** 产出已写好，前端可读。 */
    DONE,

    /** 重试次数用尽。{@code error} 里留着原因。 */
    FAILED
}
