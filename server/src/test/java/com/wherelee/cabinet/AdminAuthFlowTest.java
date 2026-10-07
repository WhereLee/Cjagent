package com.wherelee.cabinet;

import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.domain.entity.SysTenant;
import com.wherelee.cabinet.domain.entity.SysUser;
import com.wherelee.cabinet.infrastructure.mapper.SysTenantMapper;
import com.wherelee.cabinet.infrastructure.mapper.SysUserMapper;
import com.wherelee.cabinet.infrastructure.security.AdminPermissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 后台认证与授权链路（第三刀验收）。
 *
 * <p>覆盖：统一响应体的 401/403、跨端 token 不可用、权限点与角色两种判定、
 * 同用户名跨租户互不串号、refresh 一次性轮换、注销后 access 立刻失效、租户停用拒登。
 */
@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Sql(scripts = "/sql/auth-fixture.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class AdminAuthFlowTest {

    private static final String TENANT_ONE = "t-one";
    private static final String TENANT_TWO = "t-two";
    private static final String PWD_ONE = "pwd-one-123";
    private static final String PWD_TWO = "pwd-two-123";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;
    @Autowired
    private SysUserMapper userMapper;
    @Autowired
    private SysTenantMapper tenantMapper;
    @Autowired
    private AdminPermissionService permissionService;

    @BeforeEach
    void preparePasswords() {
        // 口令由测试现算，仓库里不留任何可用凭据；同时清掉权限缓存，避免上一次运行的残留
        setPassword(9301L, 8101L, PWD_ONE);
        setPassword(9302L, 8102L, PWD_TWO);
        permissionService.evict(9301L);
        permissionService.evict(9302L);
    }

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    private void setPassword(Long userId, Long tenantId, String rawPassword) {
        TenantContext.runAs(tenantId, () -> {
            SysUser user = new SysUser();
            user.setId(userId);
            user.setPasswordHash(passwordEncoder.encode(rawPassword));
            userMapper.updateById(user);
        });
    }

    private String login(String tenantCode, String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantCode":"%s","username":"%s","password":"%s"}
                                """.formatted(tenantCode, username, password)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andReturn();
        // 只取 accessToken，避免把整段 JSON 拼进断言里
        String body = result.getResponse().getContentAsString();
        int start = body.indexOf("\"accessToken\":\"") + "\"accessToken\":\"".length();
        int end = body.indexOf('"', start);
        return body.substring(start, end);
    }

    @Test
    @DisplayName("登录后 me 返回角色与权限点，探针接口按两种判定放行")
    void loginThenMeAndGuardedEndpoints() throws Exception {
        String token = login(TENANT_ONE, "ops-admin", PWD_ONE);

        mockMvc.perform(get("/api/admin/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userId").value("9301"))
                .andExpect(jsonPath("$.data.tenantId").value("8101"))
                .andExpect(jsonPath("$.data.authorities").value(org.hamcrest.Matchers.hasItems("ROLE_OPS", "probe:read")));

        // Long 必须以字符串返回（第一刀的约定在这里生效），否则前端精度丢失
        mockMvc.perform(get("/api/admin/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(jsonPath("$.data.userId").isString());

        mockMvc.perform(get("/api/admin/probe/authenticated").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/probe/permission").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/probe/role").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("未带 token 访问受保护接口：401 + 统一响应体（不是 Security 默认的空响应）")
    void anonymousGetsUnified401() throws Exception {
        mockMvc.perform(get("/api/admin/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    @DisplayName("坏 token 同样 401，不把失败原因细分给调用方")
    void garbageTokenGets401() throws Exception {
        mockMvc.perform(get("/api/admin/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    @DisplayName("refresh 令牌不能当 access 调业务接口")
    void refreshTokenCannotCallApi() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantCode":"t-one","username":"ops-admin","password":"pwd-one-123"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        int rs = body.indexOf("\"refreshToken\":\"") + "\"refreshToken\":\"".length();
        String refreshToken = body.substring(rs, body.indexOf('"', rs));

        mockMvc.perform(get("/api/admin/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + refreshToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    @DisplayName("后台 token 打骑手接口必须被拒（两条链各认各端）")
    void adminTokenRejectedOnMiniChain() throws Exception {
        String token = login(TENANT_ONE, "ops-admin", PWD_ONE);

        mockMvc.perform(get("/api/mini/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    @DisplayName("已登录但权限不足 → 403（不是 401，前端不该跳登录）")
    void authenticatedWithoutPermissionGets403() throws Exception {
        // 9302 在租户二，故意不绑任何角色
        String token = login(TENANT_TWO, "ops-admin", PWD_TWO);

        mockMvc.perform(get("/api/admin/probe/authenticated").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/probe/permission").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    @DisplayName("同名账号跨租户互不串号：口令按各自的租户校验")
    void sameUsernameAcrossTenantsIsolated() throws Exception {
        login(TENANT_ONE, "ops-admin", PWD_ONE);
        login(TENANT_TWO, "ops-admin", PWD_TWO);

        // 拿租户一的账号去试租户二的口令：必须失败，且与"用户不存在"响应完全一致
        mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantCode":"t-one","username":"ops-admin","password":"pwd-two-123"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    @DisplayName("refresh 是一次性的：换过之后旧值再用要被拒")
    void refreshIsRotatedAndReuseRejected() throws Exception {
        MvcResult loginResult = mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantCode":"t-one","username":"ops-admin","password":"pwd-one-123"}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        String loginBody = loginResult.getResponse().getContentAsString();
        int rs = loginBody.indexOf("\"refreshToken\":\"") + "\"refreshToken\":\"".length();
        String oldRefresh = loginBody.substring(rs, loginBody.indexOf('"', rs));

        MvcResult refreshed = mockMvc.perform(post("/api/admin/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + oldRefresh + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn();
        String newBody = refreshed.getResponse().getContentAsString();
        int ns = newBody.indexOf("\"refreshToken\":\"") + "\"refreshToken\":\"".length();
        String newRefresh = newBody.substring(ns, newBody.indexOf('"', ns));
        assertTrue(!newRefresh.equals(oldRefresh), "刷新必须换发新的 refresh");

        mockMvc.perform(post("/api/admin/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + oldRefresh + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));

        // 新的 refresh 仍可用
        mockMvc.perform(post("/api/admin/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + newRefresh + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("注销后同一个 access 立刻失效（JWT 删不掉，靠黑名单）")
    void logoutInvalidatesAccessToken() throws Exception {
        String token = login(TENANT_ONE, "ops-admin", PWD_ONE);
        mockMvc.perform(get("/api/admin/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/admin/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        mockMvc.perform(get("/api/admin/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    @DisplayName("租户停用后登录被拒：40301，语义上区别于凭证问题")
    void disabledTenantRejectsLogin() throws Exception {
        SysTenant tenant = tenantMapper.selectById(8102L);
        tenant.setStatus(0);
        tenantMapper.updateById(tenant);

        mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tenantCode":"t-two","username":"ops-admin","password":"pwd-two-123"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40301));
    }

    @Test
    @DisplayName("参数校验：缺字段的登录请求走 400 + 40000，不是一句 500")
    void loginValidationFailureIs400() throws Exception {
        mockMvc.perform(post("/api/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantCode\":\"t-one\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40000));
    }
}
