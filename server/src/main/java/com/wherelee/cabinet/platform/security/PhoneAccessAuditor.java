package com.wherelee.cabinet.platform.security;

import com.wherelee.cabinet.domain.entity.SysOperationLog;
import com.wherelee.cabinet.platform.audit.OperationLogWriter;
import org.springframework.stereotype.Component;

/**
 * 记录"谁在什么时候看了哪个客户的明文手机号"。
 *
 * <p>为什么单独一个组件、不塞进 {@link PhoneVisibility}：后者是纯静态判定，被单测直接调
 * （没有容器、没有数据库）。审计要写库，必须是 bean。判定与留痕分开，好处是
 * "谁能看"这条规则不会因为写日志失败而变得不可测。
 *
 * <p>记的是<b>谁看了哪个客户</b>，不记手机号本身——审计表里存明文号码，
 * 等于把需要保护的东西换个地方再放一份。
 */
@Component
public class PhoneAccessAuditor {

    private final OperationLogWriter writer;

    public PhoneAccessAuditor(OperationLogWriter writer) {
        this.writer = writer;
    }

    /**
     * @param customerId 被查看的客户；订单没关联客户时传 null，此时不记
     */
    public void record(Long customerId) {
        if (customerId == null) {
            return;
        }
        Caller caller = AuthenticatedPrincipal.currentOrNull();
        if (caller == null) {
            // 没有认证主体 = 不是"某个人在看"，也就没有可追责的对象
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
