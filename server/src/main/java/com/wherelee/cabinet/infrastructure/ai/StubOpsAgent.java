package com.wherelee.cabinet.infrastructure.ai;

import com.wherelee.cabinet.assistant.api.OpsDiagnosePort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 桩实现（默认）：AI 运维诊断未开启时的固定结构。
 *
 * <p>与客服/简报桩同一哲学：CI 与集成测试<b>永不调用付费接口</b>。
 * 接真模型只改 {@code cabinet.agent.client} 一项 + 环境变量。
 */
@Component
@ConditionalOnProperty(name = "cabinet.agent.client", havingValue = "stub", matchIfMissing = true)
public class StubOpsAgent implements OpsDiagnosePort {

    @Override
    public OpsReport diagnose() {
        return new OpsReport(
                "AI 诊断当前未开启：请直接查看 /actuator/health 与 ops-check 巡检输出。",
                List.of("AI 未开启，错误日志未读取——请人工 tail cabinet-server-error.log。"),
                List.of("AI 未开启，崩溃残留未盘点——请人工 ls logs/ | grep hs_err。"),
                List.of("开启 AI 诊断：配置 cabinet.agent.client=real 并注入 MIMO_API_KEY。"));
    }

    @Override
    public OpsAnswer ask(String question) {
        return new OpsAnswer("AI 运维问答当前未开启：请直接查看故障台账、工单流水与巡检输出。");
    }
}
