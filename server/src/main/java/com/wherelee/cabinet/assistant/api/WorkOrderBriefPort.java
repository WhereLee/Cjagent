package com.wherelee.cabinet.assistant.api;

import java.util.List;

public interface WorkOrderBriefPort {

    Brief brief(Long workOrderId, Long operatorId);

    record Brief(String summary, String draftNote, List<SimilarCase> similarCases) {

        public static Brief fallback() {
            return new Brief("AI 简报暂时无法生成。请直接查看工单的事件流水与证据时间线，"
                    + "两者就是这张单的全部事实。", null, List.of());
        }
    }

    record SimilarCase(String title, String resultNote, String resolvedAt) {
    }
}
