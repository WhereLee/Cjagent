package com.wherelee.cabinet;

import com.wherelee.cabinet.application.workorder.WorkOrderMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批次 7：指标可算且能区分“AI 解决了”与“AI 挂了”。
 *
 * <p>不断言具体比率：工单结果指标是全库窗口聚合，其他用例的数据会一起参与；
 * 这里证明计数器分桶正确、Gauge 已注册、刷新不抛错。
 */
@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest(properties = {
        "cabinet.scheduler.poll-enabled=false",
})
@DisplayName("工单指标：自助/转报/兜底分开计数，结果指标可刷新")
class WorkOrderMetricsTest {

    @Autowired
    private WorkOrderMetrics metrics;
    @Autowired
    private MeterRegistry registry;

    @Test
    @DisplayName("一次自助、一次转报、一次兜底分别落在三个桶里")
    void chatOutcomesAreCountedSeparately() {
        double answeredBefore = counter("answered");
        double escalatedBefore = counter("escalated");
        double fallbackBefore = counter("fallback");

        metrics.chat(false, false);
        metrics.chat(true, false);
        metrics.chat(false, true);

        assertEquals(answeredBefore + 1, counter("answered"), 0.0001);
        assertEquals(escalatedBefore + 1, counter("escalated"), 0.0001);
        assertEquals(fallbackBefore + 1, counter("fallback"), 0.0001,
                "AI 不可用的兜底不能算成自助解决");
    }

    @Test
    @DisplayName("结果指标已注册，刷新后是 0~1 的比率与非负时长")
    void resultGaugesAreRegisteredAndRefreshable() {
        metrics.refresh();

        assertNotNull(registry.find("cabinet.work_order.queue.backlog.total").gauge());
        assertNotNull(registry.find("cabinet.work_order.first_fix.ratio").gauge());
        assertNotNull(registry.find("cabinet.work_order.sla.attainment.ratio").gauge());
        assertNotNull(registry.find("cabinet.work_order.misreport.ratio").gauge());
        assertNotNull(registry.find("cabinet.work_order.handle.p95.seconds").gauge());
        assertNotNull(registry.find("cabinet.ai.self_service.ratio").gauge());
        assertNotNull(registry.find("cabinet.ai.escalation.ratio").gauge());

        WorkOrderMetrics.Snapshot snapshot = metrics.snapshot();
        assertTrue(snapshot.backlogOnline() >= 0);
        assertTrue(snapshot.backlogField() >= 0);
        assertTrue(snapshot.firstFixRate() >= 0 && snapshot.firstFixRate() <= 1);
        assertTrue(snapshot.slaRate() >= 0 && snapshot.slaRate() <= 1);
        assertTrue(snapshot.misreportRate() >= 0 && snapshot.misreportRate() <= 1);
        assertTrue(snapshot.p95Seconds() >= 0);
    }

    private double counter(String outcome) {
        return registry.get("cabinet.ai.chat").tag("outcome", outcome).counter().count();
    }
}
