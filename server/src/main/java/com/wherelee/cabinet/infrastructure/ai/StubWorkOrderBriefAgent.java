package com.wherelee.cabinet.infrastructure.ai;

import com.wherelee.cabinet.application.workorder.WorkOrderBriefPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 桩实现（默认）：AI 简报未开启时的固定话术。
 *
 * <p>与客服桩同一哲学：CI 与集成测试<b>永不调用付费接口</b>。接真模型只改
 * {@code cabinet.agent.client} 一项 + 环境变量。
 */
@Component
@ConditionalOnProperty(name = "cabinet.agent.client", havingValue = "stub", matchIfMissing = true)
public class StubWorkOrderBriefAgent implements WorkOrderBriefPort {

    @Override
    public Brief brief(Long workOrderId, Long operatorId) {
        return new Brief(
                "AI 简报当前未开启：请直接查看工单的事件流水与证据时间线。",
                "AI 草稿当前未开启：请依据事件流水自行填写处理结论。",
                List.of());
    }
}
