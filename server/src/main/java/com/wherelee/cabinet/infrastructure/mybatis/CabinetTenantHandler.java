package com.wherelee.cabinet.infrastructure.mybatis;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.config.CabinetProperties;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * 租户条件自动注入：给所有<b>非白名单表</b>的 SELECT / INSERT / UPDATE / DELETE 加上 {@code tenant_id = ?}。
 *
 * <p>为什么必须由拦截器做、而不是让每个 Mapper 自己写 where：漏一处就是越租读别人数据，
 * 而且这种漏洞在功能测试里看不出来（单租户环境一切正常），只有两个租户同时在线才暴露。
 *
 * <p><b>无租户上下文时拒绝执行</b>（fail fast），不返回全表：
 * 内部任务/定时任务必须显式 {@code TenantContext.runAs(租户ID, ...)}，
 * 平台级任务则应把表放进白名单或走原生 SQL。让 SQL 在"租户为空"时悄悄退化成"不带条件"，
 * 等于把越租风险变成默认行为。
 */
@Component
public class CabinetTenantHandler implements TenantLineHandler {

    /**
     * 内置白名单（另外可通过 cabinet.tenant.ignore-tables 追加）。
     * <ul>
     *   <li>{@code flyway_schema_history}：迁移表，本身没有 tenant_id</li>
     *   <li>{@code sys_tenant}：租户主表，自己的 tenant_id 就是主键，不能再被过滤</li>
     *   <li>{@code sys_permission}：权限点与菜单字典，平台维护、全租户共享（见 V3 注释）</li>
     *   <li>{@code sys_operation_log}：审计日志允许记录平台侧操作（tenant_id 可为空），
     *       <b>代价是查这张表时必须自己加租户条件</b>，拦截器帮不了</li>
     *   <li>{@code biz_cabinet_model}：柜机型号模板（硬件事实：6大/8中/10小）是全平台字典，
     *       租户只引用不拥有（S-14）。进白名单的意思是“写错了不会越租，但也不隔离”，
     *       所以它只允许平台侧维护，不给业务侧开放增删改接口</li>
     *   <li>{@code biz_msg_consume}：消费幂等记录。它必须在“信任消息里的 tenant_id”之前写入，
     *       带租户列会形成鸡生蛋环（见 V6 注释）；<b>代价是查它必须自己加租户条件</b></li>
     * </ul>
     */
    private static final Set<String> BUILTIN_IGNORE = Set.of(
            "flyway_schema_history", "sys_tenant", "sys_permission", "sys_operation_log",
            "biz_cabinet_model", "biz_msg_consume");

    private final CabinetProperties properties;

    public CabinetTenantHandler(CabinetProperties properties) {
        this.properties = properties;
    }

    @Override
    public Expression getTenantId() {
        Long tenantId = TenantContext.current();
        if (tenantId == null) {
            throw new BizException(ResultCode.TENANT_INVALID,
                    "缺少租户上下文，已拒绝执行不带 tenant_id 条件的 SQL；内部任务请用 TenantContext.runAs 显式指定");
        }
        return new LongValue(tenantId);
    }

    @Override
    public String getTenantIdColumn() {
        return properties.getTenant().getColumn();
    }

    @Override
    public boolean ignoreTable(String tableName) {
        String normalized = normalize(tableName);
        if (BUILTIN_IGNORE.contains(normalized)) {
            return true;
        }
        return properties.getTenant().getIgnoreTables().stream()
                .map(this::normalize)
                .anyMatch(normalized::equals);
    }

    /** 去掉反引号/库名前缀并统一小写，避免 `` WHERE `probe_audit`.`tenant_id` `` 这类写法绕过白名单匹配。 */
    private String normalize(String tableName) {
        if (tableName == null) {
            return "";
        }
        String name = tableName.replace("`", "").trim().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }
}
