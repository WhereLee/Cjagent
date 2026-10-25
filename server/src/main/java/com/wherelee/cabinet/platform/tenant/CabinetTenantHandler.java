package com.wherelee.cabinet.platform.tenant;

import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.app.CabinetProperties;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

@Component
public class CabinetTenantHandler implements TenantLineHandler {

    private static final Set<String> BUILTIN_IGNORE = Set.of(
            "flyway_schema_history", "sys_tenant", "sys_permission", "sys_operation_log",
            "biz_cabinet_model", "biz_dead_letter");

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

    private String normalize(String tableName) {
        if (tableName == null) {
            return "";
        }
        String name = tableName.replace("`", "").trim().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }
}
