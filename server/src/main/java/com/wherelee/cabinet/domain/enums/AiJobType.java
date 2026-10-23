package com.wherelee.cabinet.domain.enums;

/**
 * B 端 AI 作业的类型。
 *
 * <p>三种都产出一段长文本、都要几秒到十几秒，所以走同一条作业通道；
 * 差别只在“执行时该调哪个端口”，由执行器按类型分派。
 */
public enum AiJobType {

    /** 运维诊断：读健康 + 日志 + 崩溃残留 + 台账，产出巡检解读。 */
    DIAGNOSE,

    /** 运维问答：按问题检索只读事实后作答。 */
    ASK,

    /** 工单简报：归纳一张工单的事实，产出摘要与处置草稿。 */
    BRIEF
}
