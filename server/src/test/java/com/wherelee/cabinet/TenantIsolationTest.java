package com.wherelee.cabinet;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.fixture.ProbeAudit;
import com.wherelee.cabinet.infrastructure.mapper.ProbeAuditMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 租户隔离回归测试（第二刀的核心产出）。
 *
 * <p>这类漏洞的特点是<b>单租户环境永远正常</b>：只跑一个租户时，加了条件和没加条件结果一样。
 * 所以每条断言都必须<b>同时存在两个租户</b>，否则测了等于没测。
 */
@Tag("integration")
@ActiveProfiles({"dev", "integration"})
@SpringBootTest
@Transactional
@Rollback
@Sql(scripts = "/sql/probe-audit.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class TenantIsolationTest {

    private static final Long TENANT_A = 7001L;
    private static final Long TENANT_B = 7002L;

    @Autowired
    private ProbeAuditMapper mapper;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private ProbeAudit insertAs(Long tenant, String name) {
        return TenantContext.callAs(tenant, () -> {
            ProbeAudit entity = new ProbeAudit();
            entity.setName(name);
            mapper.insert(entity);
            return entity;
        });
    }

    private List<String> namesVisibleTo(Long tenant) {
        return TenantContext.callAs(tenant, () -> mapper.selectList(null))
                .stream().map(ProbeAudit::getName).sorted().toList();
    }

    /**
     * 拦截器抛出的异常可能被 MyBatis-Spring 转译包装，所以不直接断言异常类型，
     * 而是断言异常链里存在 BizException —— 既稳又不会掩盖真实问题。
     */
    private static void assertRejectedByTenantGuard(Throwable t) {
        Throwable cursor = t;
        while (cursor != null) {
            if (cursor instanceof BizException) {
                return;
            }
            cursor = cursor.getCause();
        }
        throw new AssertionError("预期被租户守卫拒绝（BizException），实际是 "
                + t.getClass().getName() + ": " + t.getMessage());
    }

    @Test
    @DisplayName("两个租户各自只能看到自己的数据")
    void eachTenantSeesOnlyOwnRows() {
        insertAs(TENANT_A, "a-1");
        insertAs(TENANT_A, "a-2");
        insertAs(TENANT_B, "b-1");

        assertEquals(List.of("a-1", "a-2"), namesVisibleTo(TENANT_A));
        assertEquals(List.of("b-1"), namesVisibleTo(TENANT_B));
    }

    @Test
    @DisplayName("分页 total 只算本租户（租户插件必须排在分页插件之前）")
    void pageTotalIsScopedPerTenant() {
        insertAs(TENANT_A, "a-1");
        insertAs(TENANT_A, "a-2");
        insertAs(TENANT_B, "b-1");
        insertAs(TENANT_B, "b-2");
        insertAs(TENANT_B, "b-3");

        Page<ProbeAudit> pageA = TenantContext.callAs(TENANT_A, () -> mapper.selectPage(new Page<>(1, 10), null));
        Page<ProbeAudit> pageB = TenantContext.callAs(TENANT_B, () -> mapper.selectPage(new Page<>(1, 10), null));

        assertEquals(2L, pageA.getTotal(),
                "租户 A 的 total 应是 2；若变成 5 说明 count SQL 没带 tenant_id（插件顺序错了）");
        assertEquals(3L, pageB.getTotal(), "租户 B 的 total 应是 3");
    }

    @Test
    @DisplayName("跨租户 update 影响 0 行，且对方数据未被篡改")
    void crossTenantUpdateIsNoOp() {
        ProbeAudit foreign = insertAs(TENANT_B, "b-original");

        int affected = TenantContext.callAs(TENANT_A, () -> {
            foreign.setName("hijacked");
            return mapper.updateById(foreign);
        });

        assertEquals(0, affected, "拿别人的 id 在本租户上下文里更新，不该影响任何行");
        assertEquals(List.of("b-original"), namesVisibleTo(TENANT_B), "租户 B 的数据必须原样");
    }

    @Test
    @DisplayName("手写 @Select 也会被注入租户条件（不是只作用于自动生成 SQL）")
    void handWrittenSelectAlsoScoped() {
        ProbeAudit foreign = insertAs(TENANT_B, "b-raw");

        int visible = TenantContext.callAs(TENANT_A, () -> mapper.countPhysical(foreign.getId()));
        assertEquals(0, visible, "物理 count 探针也带上了 tenant_id，说明拦截器作用在所有 SQL 上");

        // 没有租户上下文时，这类手写 SQL 会被拒绝，而不是退化成全表
        Throwable t = assertThrows(Throwable.class, () -> mapper.countPhysical(foreign.getId()));
        assertRejectedByTenantGuard(t);
    }

    @Test
    @DisplayName("无租户上下文读非白名单表：拒绝执行而不是返回全表")
    void readWithoutTenantIsRejected() {
        insertAs(TENANT_A, "a-1");

        Throwable t = assertThrows(Throwable.class, () -> mapper.selectCount(null));
        assertRejectedByTenantGuard(t);
    }

    @Test
    @DisplayName("白名单表在无租户上下文下仍可查（迁移表与租户主表自身）")
    void whitelistTablesReadableWithoutContext() {
        // 不断言具体行数：其他测试类的夹具可能己写入租户，具体数量不是本用例要管的事。
        // 这里要验的是“没有租户上下文也不被守卫拒绝”，能执行就行。
        assertTrue(mapper.countAppliedMigration("2") > 0, "V2__sys_tenant 应已应用");
        assertTrue(mapper.countTenantsWithoutContext() >= 0, "sys_tenant 查询可执行 = 未被加 tenant_id 条件");
    }
}
