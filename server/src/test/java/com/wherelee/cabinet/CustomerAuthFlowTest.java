package com.wherelee.cabinet;

import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.domain.entity.BizCustomer;
import com.wherelee.cabinet.infrastructure.mapper.BizCustomerMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 用户端登录链路（dev 用 mock 微信，不需要 AppID）。
 *
 * <p>验证的是"归属与状态"两件事：首次注册必须带运营商编码且不能事后改归属；
 * 冻结账号后凭证仍然有效但业务接口给 403（而不是 401，因为身份本身没问题）。
 */
@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Sql(scripts = "/sql/auth-fixture.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class CustomerAuthFlowTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private BizCustomerMapper customerMapper;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    /** 登录并返回 accessToken；断言 200 与 code=0 已在这里完成。 */
    private String login(String code, String tenantCode) throws Exception {
        String json = tenantCode == null
                ? "{\"code\":\"" + code + "\"}"
                : "{\"code\":\"" + code + "\",\"tenantCode\":\"" + tenantCode + "\"}";
        MvcResult result = mockMvc.perform(post("/api/mini/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return accessTokenOf(result.getResponse().getContentAsString());
    }

    private static String accessTokenOf(String body) {
        int start = body.indexOf("\"accessToken\":\"") + "\"accessToken\":\"".length();
        return body.substring(start, body.indexOf('"', start));
    }

    @Test
    @DisplayName("首次登录带运营商编码：注册成功，随后 me 可用")
    void firstLoginRegistersWithTenantCode() throws Exception {
        String body = mockMvc.perform(post("/api/mini/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"cust-new-01\",\"tenantCode\":\"t-one\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.newRegister").value(true))
                .andExpect(jsonPath("$.data.tenantId").value("8101"))
                // 第一刀约定：雪花 ID 以字符串返回，避开前端精度丢失
                .andExpect(jsonPath("$.data.customerId").isString())
                .andReturn().getResponse().getContentAsString();

        String token = accessTokenOf(body);
        mockMvc.perform(get("/api/mini/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.tenantId").value("8101"))
                .andExpect(jsonPath("$.data.customerId").isString())
                // 注册本次就是首次登录：不能出现"刚注册完却显示从未登录过"这种语义空缺
                .andExpect(jsonPath("$.data.lastLoginAt").isNotEmpty())
                // 实体不得直出：openId / unionId / deleted 是服务端内部字段，
                // 之前这个用例反过来断言了 openId 存在，等于把泄露固化进测试
                .andExpect(jsonPath("$.data.openId").doesNotExist())
                .andExpect(jsonPath("$.data.unionId").doesNotExist())
                .andExpect(jsonPath("$.data.deleted").doesNotExist());
    }

    @Test
    @DisplayName("首次登录不带运营商编码：403 + 40301，不允许落一个无主账号")
    void firstLoginWithoutTenantCodeRejected() throws Exception {
        mockMvc.perform(post("/api/mini/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"cust-no-tenant-01\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    @DisplayName("已注册客户不能通过传别的运营商编码改归属")
    void existingCustomerCannotSwitchTenant() throws Exception {
        login("cust-fixed-01", "t-one");

        mockMvc.perform(post("/api/mini/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"cust-fixed-01\",\"tenantCode\":\"t-two\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    @DisplayName("不存在的运营商编码：403 + 40301")
    void unknownTenantCodeRejected() throws Exception {
        mockMvc.perform(post("/api/mini/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"cust-bad-tenant\",\"tenantCode\":\"no-such-tenant\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    @DisplayName("客户被冻结：凭证仍有效但业务接口 403（区别于 401）")
    void frozenCustomerGets403Not401() throws Exception {
        String token = login("cust-frozen-01", "t-one");
        mockMvc.perform(get("/api/mini/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        Long tenantId = 8101L;
        String openId = "mock-openid-cust-frozen-01";
        TenantContext.runAs(tenantId, () -> {
            BizCustomer customer = customerMapper.selectByOpenId(openId);
            assertNotNull(customer, "夹具里刚注册的客户应当查得到");
            BizCustomer freeze = new BizCustomer();
            freeze.setId(customer.getId());
            freeze.setStatus(0);
            customerMapper.updateById(freeze);
        });
        TenantContext.clear();

        mockMvc.perform(get("/api/mini/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    @DisplayName("客户 token 打后台接口必须被拒（两条链各认各端）")
    void customerTokenRejectedOnAdminChain() throws Exception {
        String token = login("cust-cross-end", "t-one");

        mockMvc.perform(get("/api/admin/probe/authenticated").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    @DisplayName("注销后 access 立刻失效")
    void logoutInvalidatesAccess() throws Exception {
        String token = login("cust-logout-01", "t-one");

        mockMvc.perform(post("/api/mini/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/mini/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }
}
