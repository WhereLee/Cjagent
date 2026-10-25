package com.wherelee.cabinet.platform.tenant;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.entity.BaseEntity;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

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
        if (metaObject.getOriginalObject() instanceof BaseEntity entity) {
            Long explicit = entity.getTenantId();
            if (explicit != null) {
                return;
            }
            if (tenantId == null) {
                throw new BizException(ResultCode.TENANT_INVALID,
                        "写入业务实体时既无租户上下文也未显式设置 tenantId，已拒绝（防写出无主数据）");
            }
            strictInsertFill(metaObject, FIELD_TENANT_ID, Long.class, tenantId);
        } else if (tenantId != null) {
            strictInsertFill(metaObject, FIELD_TENANT_ID, Long.class, tenantId);
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        setFieldValByName(FIELD_UPDATE_TIME, LocalDateTime.now(), metaObject);
    }
}
