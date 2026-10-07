package com.wherelee.cabinet.infrastructure.mybatis;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.wherelee.cabinet.common.context.TenantContext;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 审计字段自动填充：业务代码不再手写 {@code setCreateTime(now)}。
 *
 * <p>用 {@code strictXxxFill}（只填标注了 FieldFill 且当前为 null 的字段），
 * 好处是：业务显式赋过值的字段不会被覆盖，例如数据迁移/补录场景要保留原始 create_time。
 *
 * <p>这里填的是 {@code tenantId} 的<b>写入</b>侧；查询侧的自动条件由第二刀的
 * TenantLineInnerInterceptor 完成。只做写入不做查询 = 数据归属正确但仍能读到别人的数据。
 */
@Component
public class AuditMetaObjectHandler implements MetaObjectHandler {

    private static final String FIELD_CREATE_TIME = "createTime";
    private static final String FIELD_UPDATE_TIME = "updateTime";
    private static final String FIELD_DELETED = "deleted";
    private static final String FIELD_TENANT_ID = "tenantId";

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        strictInsertFill(metaObject, FIELD_CREATE_TIME, LocalDateTime.class, now);
        strictInsertFill(metaObject, FIELD_UPDATE_TIME, LocalDateTime.class, now);
        strictInsertFill(metaObject, FIELD_DELETED, Integer.class, 0);

        Long tenantId = TenantContext.current();
        if (tenantId != null) {
            strictInsertFill(metaObject, FIELD_TENANT_ID, Long.class, tenantId);
        }
        // 租户上下文为空时不在此处报错：系统内部任务（定时任务、初始化脚本）本来就无租户，
        // 面向用户请求的强校验放在拦截器/过滤器层，那里有 HTTP 语义可以返回 40301。
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        // 不能用 strictUpdateFill：它只填 null 字段，而 updateTime 在插入时已被填过，
        // 结果是 update_time 永远停在创建时间（集成测试实际抓到了这个 bug）。
        // 更新语义下 update_time 就应该无条件刷新；真要保留原值（数据修复）请走原生 SQL。
        setFieldValByName(FIELD_UPDATE_TIME, LocalDateTime.now(), metaObject);
    }
}
