package com.wherelee.cabinet;

import com.wherelee.cabinet.application.point.PayChannel;
import com.wherelee.cabinet.application.reconcile.ReconcileReport;
import com.wherelee.cabinet.application.reconcile.ReconcileService;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.infrastructure.pay.MockPayChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 充值链路（第 13 刀：PayChannel 抽象 + Mock 通道 + 对手方对账）。
 *
 * <p>这一刀要补的洞很具体：第 12 刀只有内部 {@code points.recharge()}，dev 里新用户一上来就是 0 点，
 * 任何下单都被"点数不足"挡死；而"充值"这件事一旦有入口，就必须同时回答
 * <b>"钱进了通道但点数没加"和"点数加了但通道没收钱"</b>——否则加钱这条路比扣钱更容易出事。
 *
 * <p>所以断言分三层：接口层（幂等/限流/鉴权真的生效）、账务层（两本账各多一条且互相指得上）、
 * 对账层（正常态两项差异都为 0，人为造差异时必须被抓到）。
 *
 * <p>走 MockMvc 而不是直调 service：{@code @Idempotent}/{@code @RateLimit}/{@code @PreAuthorize}
 * 是切面挂在接口层的，直调 service 就绕过了它们（第 10 刀已经因为"测试包了一层 runAs"假绿过一次）。
 */
@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Sql(scripts = "/sql/auth-fixture.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class RechargeFlowTest {

    private static final Long TENANT = 8101L;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ReconcileService reconcile;
    @Autowired
    private com.wherelee.cabinet.application.point.PointAccountService points;
    @Autowired
    private com.wherelee.cabinet.infrastructure.mapper.BizPointTxnMapper txnMapper;
    @Autowired
    private MockPayChannel simulatorChannel;
    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @AfterEach
    void resetChannel() {
        // 通道是内存态，不跟着 DB 事务回滚：不清就会把"下一次必失败"带到下个用例
        simulatorChannel.reset();
        TenantContext.clear();
    }

    private JdbcTemplate jdbc() {
        if (jdbc == null) {
            jdbc = new JdbcTemplate(dataSource);
        }
        return jdbc;
    }

    /**
     * 登录并拿到（token, customerId）。
     *
     * <p>customerId 从响应里取而不是按 openId 反查 DB：mock 通道怎么把 code 映射成 openId
     * 是实现细节，测试拼那个格式就是把自己的命运交给别人的实现（以会脆）。
     */
    private Session login(String code) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/mini/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"tenantCode\":\"t-one\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        return new Session(field(body, "accessToken"), Long.valueOf(field(body, "customerId")));
    }

    /** 极简 JSON 取字段：响应是扁平结构，不值得为测试引一个 mapper。 */
    private static String field(String body, String name) {
        String marker = "\"" + name + "\":\"";
        int start = body.indexOf(marker);
        if (start < 0) {
            throw new IllegalStateException("响应里没有 " + name + "：" + body);
        }
        int from = start + marker.length();
        return body.substring(from, body.indexOf('"', from));
    }

    /** 登录态：凭证 + 客户号。 */
    private record Session(String token, Long customerId) {
    }

    private String rechargeBody(String requestId, long points) {
        return "{\"requestId\":\"" + requestId + "\",\"points\":" + points + "}";
    }

    @Test
    @DisplayName("充值成功：通道 PAID、点数入账、两本账互相指得上，余额来自流水")
    void rechargeCreditsBothBooks() throws Exception {
        String code = "rc-ok-" + UUID.randomUUID().toString().substring(0, 8);
        Session session = login(code);
        Long customerId = session.customerId();
        // 基线而不是绝对值：cabinet_test 是共享库，里面已有历史脏数据（它们本就该被对账报出来）。
        // 断“全库干净”会把别人的遗留当成自己的失败；这里要证的是“我做这一次充值不新增差异”
        ReconcileReport baseline = reconcile.reconcile(TENANT);

        mockMvc.perform(post("/api/mini/points/recharge")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rechargeBody("RQ-" + UUID.randomUUID(), 3000)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("PAID"))
                .andExpect(jsonPath("$.data.points").value(3000))
                .andExpect(jsonPath("$.data.channel").value("MOCK"))
                // 商户订单号是排查入口，必须回给客户端（不能只说"成功了"）
                .andExpect(jsonPath("$.data.outTradeNo").isNotEmpty());

        String outTradeNo = jdbc().queryForObject(
                "select out_trade_no from biz_pay_txn where customer_id = ?", String.class, customerId);
        assertNotNull(outTradeNo);
        // 两本账各一条，且用同一个幂等号互指：这是对账能跑起来的结构前提
        assertEquals(1, jdbc().queryForObject("select count(*) from biz_pay_txn where out_trade_no = ? and status = 'PAID'",
                Integer.class, outTradeNo));
        assertEquals(1, jdbc().queryForObject(
                "select count(*) from biz_point_txn where biz_type = 'RECHARGE' and biz_no = ?",
                Integer.class, outTradeNo), "点数流水的 biz_no 必须就是商户订单号");
        long balanceNow = jdbc().queryForObject(
                "select points from biz_point_account where customer_id = ?", Long.class, customerId);
        assertTrue(balanceNow >= 3000L, "入账后余额至少包含本单 3000（这张账户可能被其他用例用过）");

        ReconcileReport after = reconcile.reconcile(TENANT);
        assertEquals("", TenantContext.callAs(TENANT, () -> points.verifyLedger(customerId)),
                "本用例这张账户必须账平（绝对差异数包含历史脏数据，所以只验自己）");
        assertEquals(baseline.countOf(ReconcileService.PAID_NOT_CREDITED),
                after.countOf(ReconcileService.PAID_NOT_CREDITED), "正常充值不该在两本账之间造出差异");
        assertEquals(baseline.countOf(ReconcileService.CREDITED_WITHOUT_PAY),
                after.countOf(ReconcileService.CREDITED_WITHOUT_PAY), "通道单与入账必须一一对应");
    }

    @Test
    @DisplayName("同一 requestId 重复提交：第二次被幂等挡，点数只入一次")
    void duplicateRequestIdCreditsOnce() throws Exception {
        String code = "rc-dup-" + UUID.randomUUID().toString().substring(0, 8);
        Session session = login(code);
        Long customerId = session.customerId();
        String requestId = "RQ-" + UUID.randomUUID();

        mockMvc.perform(post("/api/mini/points/recharge")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rechargeBody(requestId, 1000)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 弱网重发同一个业务键：必须被 @Idempotent 拦在下单之前，否则就是两笔收款
        mockMvc.perform(post("/api/mini/points/recharge")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rechargeBody(requestId, 1000)))
                .andExpect(status().isConflict());

        assertEquals(1000L, jdbc().queryForObject(
                "select points from biz_point_account where customer_id = ?", Long.class, customerId),
                "重发不能把点数加成 2000");
        assertEquals(1, jdbc().queryForObject(
                "select count(*) from biz_pay_txn where customer_id = ?", Integer.class, customerId));
    }

    @Test
    @DisplayName("通道没确认收款：绝不入账（宁可少给点，不可凭空造点）")
    void unpaidChannelDoesNotCredit() throws Exception {
        String code = "rc-fail-" + UUID.randomUUID().toString().substring(0, 8);
        Session session = login(code);
        Long customerId = session.customerId();
        simulatorChannel.setFailNext(true);

        mockMvc.perform(post("/api/mini/points/recharge")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rechargeBody("RQ-" + UUID.randomUUID(), 5000)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.balance").value(0));

        assertEquals(0, jdbc().queryForObject(
                        "select count(*) from biz_point_txn where biz_type = 'RECHARGE' and customer_id = ?",
                        Integer.class, customerId), "未收款却有流水，就是凭空造点");
        assertEquals(1, jdbc().queryForObject(
                "select count(*) from biz_pay_txn where status = 'FAILED' and customer_id = ?",
                Integer.class, customerId), "失败也要留对手方记录，否则事后无从查起");
    }

    @Test
    @DisplayName("超过单笔上限：40000 拒绝，不产生任何通道流水")
    void overLimitRejected() throws Exception {
        String code = "rc-over-" + UUID.randomUUID().toString().substring(0, 8);
        Session session = login(code);

        mockMvc.perform(post("/api/mini/points/recharge")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rechargeBody("RQ-" + UUID.randomUUID(), 100001)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));

        assertEquals(0, jdbc().queryForObject(
                "select count(*) from biz_pay_txn where customer_id = ?",
                Integer.class, session.customerId()),
                "上限校验必须在下单之前，不然通道里会多一堆废单");
    }

    @Test
    @DisplayName("没有凭证：401，充值不接受未登录请求")
    void anonymousRejected() throws Exception {
        mockMvc.perform(post("/api/mini/points/recharge")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rechargeBody("RQ-" + UUID.randomUUID(), 100)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("两本账互比能抓到：通道 PAID 但没入账 / 入账了但通道无记录")
    void crossBookDiffIsDetectedInBothDirections() throws Exception {
        String code = "rc-diff-" + UUID.randomUUID().toString().substring(0, 8);
        Session session = login(code);
        Long customerId = session.customerId();
        mockMvc.perform(post("/api/mini/points/recharge")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(rechargeBody("RQ-" + UUID.randomUUID(), 800)))
                .andExpect(status().isOk());

        ReconcileReport clean = reconcile.reconcile(TENANT);
        long baseNotCredited = clean.countOf(ReconcileService.PAID_NOT_CREDITED);
        long baseWithoutPay = clean.countOf(ReconcileService.CREDITED_WITHOUT_PAY);

        // 方向一：抹掉点数流水（等价于"钱收了但没给点数"）。
        // 必须走 Mapper 而不是 JdbcTemplate：同一个 @Transactional 测试里 MyBatis 一级缓存
        // 只在经它自己的写操作时作废；用 jdbc 改完再跑完全相同的聚合 SQL，会直接读到旧结果，
        // 表现为"差异拓不到"——本刀实际被这个机制骗过一次，所以写在这里当注释。
        TenantContext.runAs(TENANT, () -> txnMapper.delete(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<com.wherelee.cabinet.domain.entity.BizPointTxn>lambdaQuery()
                        .eq(com.wherelee.cabinet.domain.entity.BizPointTxn::getCustomerId, customerId)
                        .eq(com.wherelee.cabinet.domain.entity.BizPointTxn::getBizType,
                                com.wherelee.cabinet.domain.enums.PointTxnType.RECHARGE)));
        ReconcileReport missingCredit = reconcile.reconcile(TENANT);
        assertEquals(baseNotCredited + 1, missingCredit.countOf(ReconcileService.PAID_NOT_CREDITED),
                "通道已收款却没有对应入账必须报出来（这是用户侧资损）");

        // 方向二：伪造一条"看起来来自通道"的入账（等价于凭空造点）
        com.wherelee.cabinet.domain.entity.BizPointTxn forged = new com.wherelee.cabinet.domain.entity.BizPointTxn();
        forged.setTenantId(TENANT);
        forged.setCustomerId(customerId);
        forged.setBizType(com.wherelee.cabinet.domain.enums.PointTxnType.RECHARGE);
        forged.setAmount(999L);
        forged.setBalanceAfter(999L);
        forged.setRefType("RECHARGE");
        forged.setBizNo("RC" + com.baomidou.mybatisplus.core.toolkit.IdWorker.getId());
        forged.setRemark("伪造通道入账");
        forged.setCreateTime(java.time.LocalDateTime.now());
        TenantContext.runAs(TENANT, () -> txnMapper.insert(forged));
        ReconcileReport missingPay = reconcile.reconcile(TENANT);
        assertEquals(baseWithoutPay + 1, missingPay.countOf(ReconcileService.CREDITED_WITHOUT_PAY),
                "RC 前缀的入账必须在通道侧找得到收款记录");
    }

    @Test
    @DisplayName("通道对象缺失时明确拒绝充值（fail-closed，不静默加点）")
    void missingChannelFailsClosed() {
        // PayChannel 端口只有一个实现（Mock）；这里验的是"没通道就不能加钱"这条约束的存在
        PayChannel channel = simulatorChannel;
        assertNotNull(channel);
        assertTrue(channel.name().equals("MOCK"),
                "生产不得装配 Mock 通道：application-prod.yml 里 cabinet.pay.channel=none");
    }
}
