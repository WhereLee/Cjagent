package com.wherelee.cabinet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.fixture.ProbeAudit;
import com.wherelee.cabinet.infrastructure.mapper.ProbeAuditMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Rollback
@Sql(scripts = "/sql/probe-audit.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class BaselineConventionsTest {

    private static final Long TEST_TENANT = 9001L;

    @Autowired
    private ProbeAuditMapper probeMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mockMvc;

    @AfterEach
    void clearTenant() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("Flyway 把正式迁移链应用到了当前库")
    void flywayApplied() {
        assertTrue(probeMapper.countAppliedMigration("1") > 0, "V1__baseline 未应用");
        assertTrue(probeMapper.countAppliedMigration("2") > 0, "V2__sys_tenant 未应用");
    }

    @Test
    @DisplayName("插入自动填 id/createTime/updateTime/deleted/tenantId")
    void insertFillsAuditFields() {
        TenantContext.set(TEST_TENANT);
        ProbeAudit entity = new ProbeAudit();
        entity.setName("probe-insert");

        int rows = probeMapper.insert(entity);

        assertEquals(1, rows);
        assertNotNull(entity.getId(), "雪花主键未生成");
        assertNotNull(entity.getCreateTime(), "createTime 未自动填充");
        assertNotNull(entity.getUpdateTime(), "updateTime 未自动填充");
        assertEquals(0, entity.getDeleted().intValue(), "deleted 默认值应为 0");
        assertEquals(TEST_TENANT, entity.getTenantId(), "tenantId 应从 TenantContext 自动填充");
        assertTrue(entity.getId() > 9_000_000_000_000_000L,
                "主键不像 19 位雪花 ID，Long→String 的必要性需重新确认: " + entity.getId());
    }

    @Test
    @DisplayName("业务显式赋值的字段不被自动填充覆盖")
    void explicitValueWins() {
        TenantContext.set(TEST_TENANT);
        LocalDateTime backdated = LocalDateTime.of(2020, 1, 1, 0, 0, 0);
        ProbeAudit entity = new ProbeAudit();
        entity.setName("probe-backdate");
        entity.setCreateTime(backdated);

        probeMapper.insert(entity);

        assertEquals(backdated, entity.getCreateTime(),
                "strictInsertFill 只填 null 字段，显式赋值必须保留（补录/迁移场景依赖它）");
    }

    @Test
    @DisplayName("更新刷新 updateTime，不动 createTime")
    void updateRefreshesUpdateTimeOnly() throws InterruptedException {
        TenantContext.set(TEST_TENANT);
        ProbeAudit entity = new ProbeAudit();
        entity.setName("probe-before");
        probeMapper.insert(entity);
        LocalDateTime created = entity.getCreateTime();

        Thread.sleep(10);
        entity.setName("probe-after");
        probeMapper.updateById(entity);

        ProbeAudit reloaded = probeMapper.selectById(entity.getId());
        assertNotNull(reloaded);
        assertEquals("probe-after", reloaded.getName());
        long driftMs = Math.abs(java.time.Duration.between(created, reloaded.getCreateTime()).toMillis());
        assertTrue(driftMs <= 2, "createTime 不该被更新动作改动，实际漂移 " + driftMs + "ms");
        assertTrue(reloaded.getUpdateTime().isAfter(reloaded.getCreateTime()),
                "updateTime 应被无条件刷新（strictUpdateFill 只填 null，不适用于更新场景）");
    }

    @Test
    @DisplayName("deleteById 是逻辑删除：物理行仍在，deleted 置 1")
    void deleteIsLogical() {
        TenantContext.set(TEST_TENANT);
        ProbeAudit entity = new ProbeAudit();
        entity.setName("probe-delete");
        probeMapper.insert(entity);
        Long id = entity.getId();

        probeMapper.deleteById(id);

        assertNull(probeMapper.selectById(id), "逻辑删除后按 id 查不到（已被 deleted=0 条件过滤）");
        assertEquals(1, probeMapper.countPhysical(id), "物理行不该消失");
        assertEquals(1, probeMapper.selectPhysicalDeleted(id).intValue(), "deleted 应被置为 1");
    }

    @Test
    @DisplayName("无租户上下文写非白名单表：直接拒绝，不允许静默写入 tenant_id 为空的脏数据")
    void insertWithoutTenantIsRejected() {
        ProbeAudit entity = new ProbeAudit();
        entity.setName("probe-no-tenant");

        Throwable thrown = assertThrows(Throwable.class, () -> probeMapper.insert(entity));
        Throwable cursor = thrown;
        while (cursor != null && !(cursor instanceof com.wherelee.cabinet.common.exception.BizException)) {
            cursor = cursor.getCause();
        }
        assertNotNull(cursor, "预期被写入侧租户守卫拒绝（BizException），实际是 "
                + thrown.getClass().getName() + ": " + thrown.getMessage());
    }

    @Test
    @DisplayName("JSON 约定：Long 输出为字符串，时间用 yyyy-MM-dd HH:mm:ss")
    void jsonConventions() throws Exception {
        ProbeAudit entity = new ProbeAudit();
        entity.setId(1948123456789012345L);
        entity.setName("json");
        entity.setCreateTime(LocalDateTime.of(2026, 10, 7, 17, 18, 53));

        String json = objectMapper.writeValueAsString(entity);

        assertTrue(json.contains("\"id\":\"1948123456789012345\""),
                "id 必须序列化成字符串，否则前端 JS 会丢精度: " + json);
        assertTrue(json.contains("\"createTime\":\"2026-10-07 17:18:53\""),
                "时间格式不符合约定: " + json);
    }

    @Test
    @DisplayName("CORS 预检：白名单内 Origin 放行并暴露自定义响应头")
    void corsPreflightPasses() throws Exception {
        mockMvc.perform(options("/api/system/health")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    @DisplayName("CORS 预检：白名单外 Origin 不得拿到放行头")
    void corsPreflightRejectsUnknownOrigin() throws Exception {
        mockMvc.perform(options("/api/system/health")
                        .header(HttpHeaders.ORIGIN, "http://evil.example.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    @DisplayName("访问日志与 traceId：真实链路靠 X-Trace-Id 头串起来")
    void traceIdExposedOnRealCall() throws Exception {
        mockMvc.perform(get("/api/system/health")
                        .header(HttpHeaders.ORIGIN, "http://localhost:5173"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(header().string("Access-Control-Expose-Headers", org.hamcrest.Matchers.containsString("X-Trace-Id")));
    }
}
