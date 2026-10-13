package com.wherelee.cabinet.application.workorder;

import java.util.List;

/**
 * B 端工单智能简报的端口（第 23 刀）：业务员的 copilot。
 *
 * <p>业务员处理工单前要一份简报，agent 一次工具循环归纳出三件：
 * 发生了什么（摘要）、结论可以怎么写（草稿）、以前怎么处理的（相似已结单）。
 * 业界同款形态（Jira/Zendesk 的 AI 摘要）给出两条边界，本项目照单全收：
 * <b>输出只给当前业务员看，不写回工单数据</b>——草稿由人修改后走既有的
 * 处理反馈端点提交，RESOLVED 仍只能由人判定；<b>可反复生成</b>——每次都是新归纳，
 * 成本靠端点频控管，不做缓存不做落库。
 *
 * <p>接口只谈业务语义，不出现任何模型/框架类型：桩或 MiMo 由 infrastructure 决定
 * （与客服通道 {@code SupportAgentPort} 同一分工）。
 */
public interface WorkOrderBriefPort {

    /**
     * 生成一份工单简报。
     *
     * <p>实现必须自行兜住一切失败——AI 不可用返回兜底 Brief，绝不能把异常抛成 5xx
     * 挡住业务员处理工单的路。{@code operatorId} 只用于审计归属，模型不可见。
     */
    Brief brief(Long workOrderId, Long operatorId);

    /**
     * 简报三件套。{@code similarCases} 可为空：同格口同类型没有已处理历史就是没有，
     * 允许 AI 说"暂无记录"，不许它拿别的单充数。
     */
    record Brief(String summary, String draftNote, List<SimilarCase> similarCases) {

        /** AI 不可用（超时/异常/空输出）时的兜底：引导业务员直接看既有留痕，绝不 5xx。 */
        public static Brief fallback() {
            return new Brief("AI 简报暂时无法生成。请直接查看工单的事件流水与证据时间线，"
                    + "两者就是这张单的全部事实。", null, List.of());
        }
    }

    /** 一条相似案例：当初的标题、处理结论原文、完结日期。只引用，不改写。 */
    record SimilarCase(String title, String resultNote, String resolvedAt) {
    }
}
