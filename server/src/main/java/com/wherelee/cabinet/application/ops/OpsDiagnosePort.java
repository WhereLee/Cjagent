package com.wherelee.cabinet.application.ops;

import java.util.List;

/**
 * 运维诊断副驾的端口（第 24 刀）：agent 用在"代码运维"上的落地。
 *
 * <p>调研定下的边界原样进接口语义：<b>诊断权给 AI，执行权留给人</b>——
 * 业界生产先例（AWS NotiOps）的运维 agent 运行时工具全部只读，本端口的两个入口
 * 也只产出"给人看的报告/回答"：建议是草稿文本，重启/清理/改配置永远是人的动作。
 *
 * <p>接口只谈业务语义，不出现任何模型/框架类型：桩或 MiMo 由 infrastructure 决定
 * （与客服、简报通道同一分工）。
 */
public interface OpsDiagnosePort {

    /**
     * 生成一份运维诊断报告（巡检解读 + GC/OOM 初判 + 错误日志聚类 + 崩溃残留盘点）。
     *
     * <p>实现必须自行兜住一切失败——AI 不可用返回兜底报告，绝不能把异常抛成 5xx
     * 把运维挡在诊断门外。报告不落库：每次都是新归纳，成本靠端点频控管。
     */
    OpsReport diagnose();

    /** 运维自然语言问答（如"这台柜机最近为什么慢"）：只归纳工具返回的事实。 */
    OpsAnswer ask(String question);

    /**
     * 诊断报告四件。{@code advice} 是建议动作清单（草稿）：每条 = 现象 + 疑因 +
     * 建议核实/动作，由人判断后手动执行——报告里永远不会出现"已执行"。
     */
    record OpsReport(String healthSummary, List<String> logFindings,
                     List<String> crashArtifacts, List<String> advice) {

        /** AI 不可用（超时/异常/空输出）时的兜底：引导走既有巡检与手册，绝不 5xx。 */
        public static OpsReport fallback() {
            return new OpsReport(
                    "AI 诊断暂时无法生成。请直接查看 /actuator/health 与 ops-check 巡检输出，"
                            + "或按运维手册 §7 三板斧人工排查。",
                    List.of(), List.of(), List.of());
        }
    }

    /** 运维问答的一句话回答。 */
    record OpsAnswer(String answer) {

        public static OpsAnswer fallback() {
            return new OpsAnswer("AI 问答暂时无法应答。请直接查看故障台账与工单流水，或稍后再试。");
        }
    }
}
