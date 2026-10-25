package com.wherelee.cabinet.assistant.api;

import java.util.List;

public interface OpsDiagnosePort {

    OpsReport diagnose();

    OpsAnswer ask(String question);

    record OpsReport(String healthSummary, List<String> logFindings,
                     List<String> crashArtifacts, List<String> advice) {

        public static OpsReport fallback() {
            return new OpsReport(
                    "AI 诊断暂时无法生成。请直接查看 /actuator/health 与 ops-check 巡检输出，"
                            + "或按运维手册 §7 三板斧人工排查。",
                    List.of(), List.of(), List.of());
        }
    }

    record OpsAnswer(String answer) {

        public static OpsAnswer fallback() {
            return new OpsAnswer("AI 问答暂时无法应答。请直接查看故障台账与工单流水，或稍后再试。");
        }
    }
}
