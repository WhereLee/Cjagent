package com.wherelee.cabinet.assistant.api;

import com.wherelee.cabinet.domain.enums.AiJobType;

import java.time.LocalDateTime;

/**
 * B 端 AI 作业：提交拿作业号，前端按作业号轮询结果。
 *
 * <p>为什么要作业化：{@code /diagnose}、{@code /ask}、{@code /ai-brief} 都是“调模型 → 同步返回几千字”，
 * 真模型实测 9.7~19.1 秒。请求线程被占着这段时间，同一个容器线程就服务不了别人。
 * 改成作业之后，请求线程只做两件事：登记、返回作业号。
 *
 * <p>为什么不让它“异步但只回一个空壳”：用户需要看到产出，所以作业号必须有可轮询的结果，
 * 而不是“提交成功，请稍后自己再点一次”。
 */
public interface AiJobPort {

    /**
     * 提交一次作业。若同一请求（同类型 + 同入参 + 同提交人）还有<b>没跑完</b>的作业，
     * 直接返回那个作业号，不新建——这就是防双击的全部实现，不需要时间窗口。
     *
     * @param input 入参。<b>三种类型语义不同</b>，由实现按类型解释：
     *              诊断无入参（传 {\@code null}）、问答传问题正文、简报传工单号。
     *              实现负责从它推出请求键与载荷——调用方不该关心唯一索引里存什么。
     * @return 作业号
     */
    Long submit(AiJobType jobType, String input, Long requesterId);

    /**
     * 查一次作业。AI 产出是 copilot 形态、<b>仅提交人可见</b>，所以这里必须校验提交人。
     *
     * @return 作业视图；不存在、已删、或不是这个人的作业，一律 {@code null}
     */
    AiJobView view(Long jobId, Long requesterId);

    /**
     * 作业视图。
     *
     * @param status {@code SUBMITTED}/{@code RUNNING} 时前端继续轮询；{@code DONE} 读 result；
     *               {@code FAILED} 提示失败原因
     * @param result 已反序列化的产出（报告/答案/简报），{@code DONE} 之前为 {@code null}
     */
    record AiJobView(Long id, String jobType, String status, Object result, String error,
                LocalDateTime createTime, LocalDateTime updateTime) {
    }
}
