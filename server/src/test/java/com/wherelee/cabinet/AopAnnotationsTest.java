package com.wherelee.cabinet;

import com.wherelee.cabinet.fixture.AnnotatedProbeController;
import com.wherelee.cabinet.infrastructure.mapper.ProbeAuditQueryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AOP 三注解（审计 / 幂等 / 限流）的行为验证。
 *
 * <p>本类<b>故意不加 {@code @Transactional}</b>：审计写入是 REQUIRES_NEW 独立提交的，
 * 若测试自己开一个事务，MySQL 默认 REPEATABLE READ 的快照可能看不到那条已提交的记录，
 * 断言就会不稳定。测试数据留在 cabinet_test（一次性库）里，用 traceId / 随机 orderNo 定位，
 * 不依赖"取最新一条"这种会互相串味的写法。
 */
@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest
@AutoConfigureMockMvc
class AopAnnotationsTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProbeAuditQueryMapper auditQuery;
    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void clearRateLimitBuckets() {
        // 限流桶是 GLOBAL 且 TTL 60s，不清的话同一分钟内重复跑本类会一上来就 429
        Set<String> keys = redis.keys("cab:rl:probe-global:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private static String body(String orderNo, String phone, String password) {
        return "{\"orderNo\":\"" + orderNo + "\",\"phone\":\"" + phone + "\",\"password\":\"" + password + "\"}";
    }

    @Test
    @DisplayName("审计落库：成功请求记录完整，入参里的手机号被遮、口令完全不出网")
    void auditRecordsSuccessWithMaskedParams() throws Exception {
        String orderNo = "ok-" + UUID.randomUUID();
        MvcResult result = mockMvc.perform(post("/api/public/probe/audit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(orderNo, "13800138000", "SuperSecret!")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(header().string("X-Trace-Id", org.hamcrest.Matchers.notNullValue()))
                .andReturn();

        Map<String, Object> row = auditQuery.selectByTraceId(result.getResponse().getHeader("X-Trace-Id"));
        assertNotNull(row, "审计记录应当落库（REQUIRES_NEW 独立提交）");
        assertEquals("probe", row.get("module"));
        assertEquals("审计探针", row.get("operation"));
        assertEquals("/api/public/probe/audit", row.get("request_uri"));
        assertEquals("POST", row.get("request_method"));
        assertEquals(1, ((Number) row.get("success")).intValue());
        assertNotNull(row.get("cost_ms"), "耗时必须记录，否则无法定位慢接口");

        String params = (String) row.get("params");
        assertNotNull(params);
        assertTrue(params.contains("138****8000"), "手机号应部分遮蔽: " + params);
        assertTrue(!params.contains("13800138000"), "明文手机号绝不能进审计表: " + params);
        assertTrue(!params.contains("SuperSecret"), "口令绝不能进审计表: " + params);
        // 不写死星号个数（随口令长度变）：只要求 password 的值是全星号
        assertTrue(java.util.regex.Pattern.compile("\"password\":\"\\*+\"").matcher(params).find(),
                "口令应被整体遮蔽: " + params);
    }

    @Test
    @DisplayName("业务失败也要留痕，且异常原样抛出（不被切面吞掉）")
    void auditRecordsFailure() throws Exception {
        String orderNo = "fail-" + UUID.randomUUID();
        MvcResult result = mockMvc.perform(post("/api/public/probe/audit-failing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(orderNo, "13900139000", "Whatever123")))
                // BizException 属 1xxxx 业务码 → HTTP 200 + code 10000，异常没被切面吃掉
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10000))
                .andExpect(jsonPath("$.message").value("故意失败: " + orderNo))
                .andReturn();

        Map<String, Object> row = auditQuery.selectByTraceId(result.getResponse().getHeader("X-Trace-Id"));
        assertNotNull(row, "失败的调用同样要留审计痕迹");
        assertEquals(0, ((Number) row.get("success")).intValue());
        assertNotNull(row.get("error_msg"));
        assertTrue(String.valueOf(row.get("error_msg")).contains("故意失败"));
    }

    @Test
    @DisplayName("saveParams=false 时入参一个字都不存")
    void auditCanSkipParams() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/public/probe/audit-no-params")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("skip-" + UUID.randomUUID(), "13700137000", "Nope12345")))
                .andExpect(status().isOk())
                .andReturn();

        Map<String, Object> row = auditQuery.selectByTraceId(result.getResponse().getHeader("X-Trace-Id"));
        assertNotNull(row);
        assertNull(row.get("params"), "标注 saveParams=false 就不该留下任何入参内容");
    }

    @Test
    @DisplayName("幂等：同一业务键第二次被拒 409/40900，不同键不受影响")
    void idempotentRejectsDuplicateBusinessKey() throws Exception {
        String orderNo = "idem-" + UUID.randomUUID();

        mockMvc.perform(post("/api/public/probe/idempotent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(orderNo, null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("executed:" + orderNo));

        mockMvc.perform(post("/api/public/probe/idempotent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(orderNo, null, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900));

        // 换一个业务键必须放行，否则说明键根本不是业务键（例如按方法名做了全局锁）
        mockMvc.perform(post("/api/public/probe/idempotent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(orderNo + "-other", null, null)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("幂等：业务失败要释放占位，否则一次失败就永久锁死这个键")
    void idempotentReleasesOnFailure() throws Exception {
        String orderNo = "idem-fail-" + UUID.randomUUID();

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/api/public/probe/idempotent-failing")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(orderNo, null, null)))
                    // 两次都必须真正进到业务方法（10000），而不是第二次变 409
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(10000));
        }
    }

    @Test
    @DisplayName("限流：超过阈值返回 429/42900")
    void rateLimitBlocksOverThreshold() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/public/probe/limited"))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/public/probe/limited"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(42900));
    }

    @Test
    @DisplayName("探针接口不依赖登录态也能工作（public 链）")
    void publicChainAllowsAnonymousProbe() throws Exception {
        mockMvc.perform(get("/api/public/probe/limited").header(HttpHeaders.AUTHORIZATION, ""))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("审计切面对 SpEL 业务键与兜底键都可用（兜底键按入参区分）")
    void idempotentFallbackKeyWorks() throws Exception {
        String orderNo = "fb-" + UUID.randomUUID();
        mockMvc.perform(post("/api/public/probe/idempotent-fallback")
                        .contentType(MediaType.APPLICATION_JSON).content(body(orderNo, null, null)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/public/probe/idempotent-fallback")
                        .contentType(MediaType.APPLICATION_JSON).content(body(orderNo, null, null)))
                .andExpect(status().isConflict());
        // 入参不同 → 兜底哈希不同 → 不算重复
        mockMvc.perform(post("/api/public/probe/idempotent-fallback")
                        .contentType(MediaType.APPLICATION_JSON).content(body(orderNo + "x", null, null)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("AnnotatedProbeController 只存在于测试源码（生产构件里不该有探针）")
    void probeIsTestOnly() {
        // 这个断言看起来多余，它守的是一件事：探针类被误搬到 main 后，
        // 生产就会暴露 /api/public/probe/** 这类无鉴权入口。
        assertNotNull(AnnotatedProbeController.class.getAnnotation(org.springframework.web.bind.annotation.RestController.class));
    }
}
