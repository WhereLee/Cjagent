package com.wherelee.cabinet.infrastructure.mybatis;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.entity.BaseEntity;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 审计字段自动填充：业务代码不再手写 {@code setCreateTime(now)}。
 *
 * <p>用 {@code strictXxxFill}（只填标注了 FieldFill 且当前为 null 的字段），
 * 好处是：业务显式赋过值的字段不会被覆盖，例如数据迁移/补录场景要保留原始 create_time。
 *
 * <p>例外：{@code updateTime} 必须无条件刷新（见 updateFill 里的说明）。
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
        if (metaObject.getOriginalObject() instanceof BaseEntity) {
            // BaseEntity 子类的 tenant_id 必须有值：因为它标了 fill=INSERT，这一列**总是出现在**
            // INSERT 语句里，TenantLineInnerInterceptor 看到列已存在就会跳过注入与校验，
            // 参数为 null 就直接写入 null（集成测试实测到：无上下文插入不报错，写出黑洞数据）。
            // 所以写入侧的守卫必须在填充层自己做，不能指望租户插件。
            if (tenantId == null) {
                throw new BizException(ResultCode.TENANT_INVALID,
                        "写入业务实体缺少租户上下文，已拒绝；内部任务请用 TenantContext.runAs 显式指定");
            }
            strictInsertFill(metaObject, FIELD_TENANT_ID, Long.class, tenantId);
        } else if (tenantId != null) {
            // 非 BaseEntity 实体（平台级表）：有上下文就顺手填上，没有那么交由租户插件处理
            strictInsertFill(metaObject, FIELD_TENANT_ID, Long.class, tenantId);
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        // 不能用 strictUpdateFill：它只填 null 字段，而 updateTime 在插入时已被填过，
        // 结果是 update_time 永远停在创建时间（集成测试实际抓到了这个 bug）。
        // 更新语义下 update_time 就应该无条件刷新；真要保留原值（数据修复）请走原生 SQL。
        setFieldValByName(FIELD_UPDATE_TIME, LocalDateTime.now(), metaObject);
    }
}
