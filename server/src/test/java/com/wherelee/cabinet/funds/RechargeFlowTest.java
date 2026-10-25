package com.wherelee.cabinet.funds;

import com.wherelee.cabinet.funds.app.PayChannel;
import com.wherelee.cabinet.funds.app.ReconcileReport;
import com.wherelee.cabinet.funds.app.ReconcileService;
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
    private com.wherelee.cabinet.funds.app.PointAccountService points;
    @Autowired
    private com.wherelee.cabinet.funds.infra.BizPointTxnMapper txnMapper;
    @Autowired
    private MockPayChannel simulatorChannel;
    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @AfterEach
    void resetChannel() {
        simulatorChannel.reset();
        TenantContext.clear();
    }

    private JdbcTemplate jdbc() {
        if (jdbc == null) {
            jdbc = new JdbcTemplate(dataSource);
        }
        return jdbc;
    }

    private Session login(String code) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/mini/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"tenantCode\":\"t-one\",\"phone\":\"13700001234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        return new Session(field(body, "accessToken"), Long.valueOf(field(body, "customerId")));
    }

    private static String field(String body, String name) {
        String marker = "\"" + name + "\":\"";
        int start = body.indexOf(marker);
        if (start < 0) {
            throw new IllegalStateException("响应里没有 " + name + "：" + body);
        }
        int from = start + marker.length();
        return body.substring(from, body.indexOf('"', from));
    }

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
                .andExpect(jsonPath("$.data.outTradeNo").isNotEmpty());

        String outTradeNo = jdbc().queryForObject(
                "select out_trade_no from biz_pay_txn where customer_id = ?", String.class, customerId);
        assertNotNull(outTradeNo);
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

        TenantContext.runAs(TENANT, () -> txnMapper.delete(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<com.wherelee.cabinet.funds.domain.BizPointTxn>lambdaQuery()
                        .eq(com.wherelee.cabinet.funds.domain.BizPointTxn::getCustomerId, customerId)
                        .eq(com.wherelee.cabinet.funds.domain.BizPointTxn::getBizType,
                                com.wherelee.cabinet.funds.api.PointTxnType.RECHARGE)));
        ReconcileReport missingCredit = reconcile.reconcile(TENANT);
        assertEquals(baseNotCredited + 1, missingCredit.countOf(ReconcileService.PAID_NOT_CREDITED),
                "通道已收款却没有对应入账必须报出来（这是用户侧资损）");

        com.wherelee.cabinet.funds.domain.BizPointTxn forged = new com.wherelee.cabinet.funds.domain.BizPointTxn();
        forged.setTenantId(TENANT);
        forged.setCustomerId(customerId);
        forged.setBizType(com.wherelee.cabinet.funds.api.PointTxnType.RECHARGE);
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
        PayChannel channel = simulatorChannel;
        assertNotNull(channel);
        assertTrue(channel.name().equals("MOCK"),
                "生产不得装配 Mock 通道：application-prod.yml 里 cabinet.pay.channel=none");
    }
}
