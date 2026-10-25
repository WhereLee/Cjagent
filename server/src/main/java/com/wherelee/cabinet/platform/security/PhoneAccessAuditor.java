package com.wherelee.cabinet.platform.security;

import com.wherelee.cabinet.domain.entity.SysOperationLog;
import com.wherelee.cabinet.platform.audit.OperationLogWriter;
import org.springframework.stereotype.Component;

@Component
public class PhoneAccessAuditor {

    private final OperationLogWriter writer;

    public PhoneAccessAuditor(OperationLogWriter writer) {
        this.writer = writer;
    }

    public void record(Long customerId) {
        if (customerId == null) {
            return;
        }
        Caller caller = AuthenticatedPrincipal.currentOrNull();
        if (caller == null) {
            return;
        }
        SysOperationLog log = new SysOperationLog();
        log.setModule("PII");
        log.setOperation("查看明文手机号");
        log.setOperatorId(caller.subjectId());
        log.setParams("customerId=" + customerId);
        log.setSuccess(1);
        writer.write(log);
    }
}
