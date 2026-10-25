package com.wherelee.cabinet.assistant;

import com.wherelee.cabinet.assistant.api.OpsDiagnosePort;
import com.wherelee.cabinet.assistant.api.SupportAgentPort;
import com.wherelee.cabinet.assistant.api.WorkOrderBriefPort;
import com.wherelee.cabinet.common.context.TenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(properties = {
        "cabinet.agent.client=real",
        "cabinet.dispute.review.client=mimo"
})
@ActiveProfiles("dev")
@EnabledIfEnvironmentVariable(named = "MIMO_API_KEY", matches = ".+")
class MimoRealCallTest {

    @Autowired
    private OpsDiagnosePort ops;

    @Autowired
    private WorkOrderBriefPort briefs;

    @Autowired
    private SupportAgentPort support;

    @Autowired
    private DataSource dataSource;

    private Long tenantId() {
        return new JdbcTemplate(dataSource).queryForObject("select id from sys_tenant limit 1", Long.class);
    }

    @Test
    @DisplayName("运维诊断：读真实日志与台账，出结构化报告")
    void diagnose() {
        long started = System.currentTimeMillis();
        OpsDiagnosePort.OpsReport report = TenantContext.callAs(tenantId(), ops::diagnose);
        long cost = System.currentTimeMillis() - started;

        System.out.println("=== [F-04] ops.diagnose cost=" + cost + "ms");
        System.out.println("healthSummary=" + report.healthSummary());
        System.out.println("logFindings=" + report.logFindings());
        System.out.println("crashArtifacts=" + report.crashArtifacts());
        System.out.println("advice=" + report.advice());
        assertNotNull(report, "诊断不能返回 null（失败也会给 fallback）");
    }

    @Test
    @DisplayName("运维问答：同一 agent 的问答通道")
    void ask() {
        long started = System.currentTimeMillis();
        Long tid = tenantId();
        OpsDiagnosePort.OpsAnswer answer = TenantContext.callAs(tid,
                () -> ops.ask("最近出过什么故障？只说你查到的事实。"));
        long cost = System.currentTimeMillis() - started;

        System.out.println("=== [F-04] ops.ask cost=" + cost + "ms");
        System.out.println("answer=" + answer.answer());
        assertNotNull(answer);
    }

    @Test
    @DisplayName("工单简报：拿一条真实工单，出摘要与草稿")
    void brief() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<Long> ids = jdbc.queryForList("select id from biz_work_order limit 1", Long.class);
        if (ids.isEmpty()) {
            System.out.println("=== [F-04] brief skipped: 库里没有工单");
            return;
        }
        long started = System.currentTimeMillis();
        Long orderId = ids.get(0);
        WorkOrderBriefPort.Brief brief = TenantContext.callAs(tenantId(),
                () -> briefs.brief(orderId, 1L));
        long cost = System.currentTimeMillis() - started;

        System.out.println("=== [F-04] brief cost=" + cost + "ms workOrderId=" + ids.get(0));
        System.out.println("summary=" + brief.summary());
        System.out.println("draftNote=" + brief.draftNote());
        System.out.println("similarCases=" + brief.similarCases());
        assertNotNull(brief);
    }

    @Test
    @DisplayName("C 端客服：拿一个真实客户问一句话，看它自己查单还是兜底")
    void chat() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<Long> customers = jdbc.queryForList("select id from biz_customer limit 1", Long.class);
        if (customers.isEmpty()) {
            System.out.println("=== [F-04] chat skipped: 库里没有客户");
            return;
        }
        long started = System.currentTimeMillis();
        Long customerId = customers.get(0);
        SupportAgentPort.AgentReply reply = TenantContext.callAs(tenantId(),
                () -> support.chat(new SupportAgentPort.SupportSession(
                        customerId, null, "我的柜子打不开了，怎么办？")));
        long cost = System.currentTimeMillis() - started;

        System.out.println("=== [F-04] chat cost=" + cost + "ms customerId=" + customers.get(0));
        System.out.println("reply=" + reply.reply());
        System.out.println("escalated=" + reply.escalated() + " ticketNo=" + reply.ticketNo()
                + " degraded=" + reply.degraded());
        assertNotNull(reply);
    }
}
