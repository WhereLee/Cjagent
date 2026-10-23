package com.wherelee.cabinet.assistant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherelee.cabinet.assistant.api.SupportAgentPort;
import com.wherelee.cabinet.common.context.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AI 客服金问题集（离线评测）：<b>只在显式开启且配置了模型密钥时运行</b>。
 *
 * <p>为什么单独一套而不是并进日常用例：真模型结论不可重现、要花钱、可能限流；
 * CI 与默认测试只跑确定性用例。这里由 {@code -Dai.eval=true} 手动触发，
 * 每条的回复都打印出来供人工复核语气与口径。
 *
 * <p>断言只覆盖<b>硬边界</b>：该转人工的必须转、回复不得泄露手机号/密钥、不得承诺退款赔偿、
 * 计费事实必须来自 wiki。措辞自由，不做逐字匹配。
 */
@Tag("integration")
@Tag("ai-eval")
@EnabledIfSystemProperty(named = "ai.eval", matches = "true")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest(properties = {
        "cabinet.agent.client=real",
        "cabinet.scheduler.poll-enabled=false",
})
@DisplayName("AI 金问题集：离线评测（需 -Dai.eval=true 与模型密钥）")
@Timeout(value = 20, unit = TimeUnit.MINUTES)
class AiEvalTest {

    private static final Long TENANT = 8173L;
    private static final Long CUSTOMER = 81730001L;
    private static final String PHONE = "13900008888";

    @Autowired
    private SupportAgentPort support;
    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;
    private Long cabinetId;
    private String orderNo;

    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(dataSource);
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        long siteId = ThreadLocalRandom.current().nextLong(81_7300_0000L, 89_9999_9999L);
        cabinetId = siteId + 1;
        long slotId = siteId + 2;
        long orderId = siteId + 3;
        orderNo = "AE-SO-" + suffix;

        jdbc.update("insert into biz_site (id, tenant_id, site_code, name, category, status, create_time, update_time, deleted) "
                        + "values (?, ?, ?, 'AI 评测点位', 'MALL', 1, NOW(3), NOW(3), 0)",
                siteId, TENANT, "AE-" + suffix);
        jdbc.update("insert into biz_cabinet (id, tenant_id, site_id, cabinet_no, name, model_id, cabinet_status, online_state, create_time, update_time, deleted) "
                        + "values (?, ?, ?, ?, 'AI 评测柜机', 90001, 'ENABLED', 'ONLINE', NOW(3), NOW(3), 0)",
                cabinetId, TENANT, siteId, "CAB-AE-" + suffix);
        jdbc.update("insert into biz_compartment (id, tenant_id, cabinet_id, slot_no, size_type, status, "
                        + "door_open_at, anomaly, current_order_id, create_time, update_time, deleted) "
                        + "values (?, ?, ?, ?, 'SMALL', 'OCCUPIED', NULL, NULL, ?, NOW(3), NOW(3), 0)",
                slotId, TENANT, cabinetId, "A" + suffix.substring(0, 4), orderId);
        jdbc.update("insert into biz_customer (id, tenant_id, open_id, nickname, phone, status, register_time, create_time, update_time, deleted) "
                        + "values (?, ?, ?, 'AI 评测用户', ?, 1, NOW(3), NOW(3), NOW(3), 0)",
                CUSTOMER, TENANT, "ae-" + suffix, PHONE);
        jdbc.update("insert into biz_storage_order (id, tenant_id, order_no, site_id, cabinet_id, slot_id, size_type, "
                        + "customer_id, status, voucher_code, temp_open_count, deposit_points, frozen_points, settled_points, "
                        + "arrears_points, create_time, update_time, deleted, active_flag) "
                        + "values (?, ?, ?, ?, ?, ?, 'SMALL', ?, 'ACTIVE', 'V82345678', 0, 0, 0, 0, 0, NOW(3), NOW(3), 0, 1)",
                orderId, TENANT, orderNo, siteId, cabinetId, slotId, CUSTOMER);
    }

    @AfterEach
    void cleanup() {
        jdbc.update("delete from biz_work_order_event where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_work_order where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_storage_order where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_compartment where cabinet_id = ?", cabinetId);
        jdbc.update("delete from biz_cabinet where id = ?", cabinetId);
        jdbc.update("delete from biz_customer where tenant_id = ?", TENANT);
        jdbc.update("delete from biz_site where tenant_id = ?", TENANT);
        TenantContext.clear();
    }

    @Test
    @DisplayName("金问题集逐条跑：转人工、事实口径、禁泄露")
    void runGoldenCases() throws Exception {
        List<EvalCase> cases = loadCases();
        int limit = Integer.getInteger("ai.eval.limit", 0);
        if (limit > 0) {
            cases = cases.subList(0, Math.min(limit, cases.size()));
        }
        String only = System.getProperty("ai.eval.only");
        if (only != null && !only.isBlank()) {
            List<String> ids = List.of(only.split(","));
            cases = cases.stream().filter(c -> ids.contains(c.id())).toList();
        }
        List<String> failures = new ArrayList<>();
        int index = 0;
        for (EvalCase c : cases) {
            index++;
            // 每条之间清掉工单，保证“声称转报”一定是本条真的建了单
            jdbc.update("delete from biz_work_order_event where tenant_id = ?", TENANT);
            jdbc.update("delete from biz_work_order where tenant_id = ?", TENANT);
            // 门打不开这条需要设备不可用，重开失败才会转报；跑完恢复
            if ("door-cannot-open".equals(c.id())) {
                jdbc.update("update biz_cabinet set online_state = 'OFFLINE' where id = ?", cabinetId);
            }

            SupportAgentPort.AgentReply reply = TenantContext.callAs(TENANT, () ->
                    support.chat(new SupportAgentPort.SupportSession(
                            CUSTOMER, c.withOrder() ? orderNo : null, c.message())));
            String text = reply == null || reply.reply() == null ? "" : reply.reply();
            String normalized = text.replaceAll("\\s+", "");

            System.out.println("[AI-EVAL] " + index + "/" + cases.size() + " " + c.id()
                    + " escalated=" + (reply != null && reply.escalated()) + "\n" + text);

            if (text.isBlank()) {
                failures.add(c.id() + "：回复为空");
            }
            if (c.expectEscalated() != null && reply != null
                    && c.expectEscalated() != reply.escalated()) {
                failures.add(c.id() + "：转人工期望 " + c.expectEscalated()
                        + " 实际 " + reply.escalated());
            }
            if (c.expectEscalated() != null && c.expectEscalated() && reply != null
                    && reply.escalated()) {
                Integer tickets = jdbc.queryForObject(
                        "select count(*) from biz_work_order where tenant_id = ?", Integer.class, TENANT);
                if (tickets == null || tickets < 1) {
                    failures.add(c.id() + "：声称转报但没有建单");
                }
            }
            for (String must : c.mustContain()) {
                if (!normalized.contains(must.replaceAll("\\s+", ""))) {
                    failures.add(c.id() + "：缺少事实「" + must + "」");
                }
            }
            if (!c.mustContainAny().isEmpty()) {
                boolean any = c.mustContainAny().stream()
                        .anyMatch(m -> normalized.contains(m.replaceAll("\\s+", "")));
                if (!any) {
                    failures.add(c.id() + "：至少应包含其一 " + c.mustContainAny());
                }
            }
            for (String forbidden : c.mustNotContain()) {
                if (normalized.contains(forbidden.replaceAll("\\s+", ""))) {
                    failures.add(c.id() + "：出现禁止内容「" + forbidden + "」");
                }
            }
            String apiKey = System.getenv("MIMO_API_KEY");
            if (apiKey != null && apiKey.length() >= 8
                    && normalized.contains(apiKey.substring(0, 8))) {
                failures.add(c.id() + "：回复泄露了模型密钥");
            }
            if ("door-cannot-open".equals(c.id())) {
                jdbc.update("update biz_cabinet set online_state = 'ONLINE' where id = ?", cabinetId);
            }
        }
        assertTrue(failures.isEmpty(), "金问题集未通过：\n" + String.join("\n", failures));
    }

    private List<EvalCase> loadCases() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/ai-eval/cases.json")) {
            if (in == null) {
                throw new IllegalStateException("缺少 /ai-eval/cases.json");
            }
            return new ObjectMapper().readValue(in, new TypeReference<List<EvalCase>>() {
            });
        }
    }

    /** 一条金问题：期望的转人工（null=不判定）、必含事实、任一命中、禁含内容。 */
    record EvalCase(String id, String message, boolean withOrder, Boolean expectEscalated,
                    List<String> mustContain, List<String> mustContainAny,
                    List<String> mustNotContain, String note) {

        EvalCase {
            mustContain = mustContain == null ? List.of() : mustContain;
            mustContainAny = mustContainAny == null ? List.of() : mustContainAny;
            mustNotContain = mustNotContain == null ? List.of() : mustNotContain;
        }
    }
}
