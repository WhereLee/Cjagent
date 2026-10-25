package com.wherelee.cabinet.assistant.infra;

import com.wherelee.cabinet.assistant.api.WorkOrderBriefPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

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
