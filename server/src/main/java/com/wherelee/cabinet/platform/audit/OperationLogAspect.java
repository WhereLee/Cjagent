package com.wherelee.cabinet.platform.audit;

import com.wherelee.cabinet.common.annotation.OperationLog;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.common.mask.MaskUtils;
import com.wherelee.cabinet.platform.web.WebUtils;
import com.wherelee.cabinet.domain.entity.SysOperationLog;
import com.wherelee.cabinet.platform.security.AuthenticatedPrincipal;
import com.wherelee.cabinet.platform.security.Caller;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Arrays;

@Aspect
@Component
@Order(-200)
public class OperationLogAspect {

    private static final Logger log = LoggerFactory.getLogger(OperationLogAspect.class);
    private static final int ERROR_MSG_LIMIT = 480;

    private final OperationLogWriter writer;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    public OperationLogAspect(OperationLogWriter writer, com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        this.writer = writer;
        this.objectMapper = objectMapper;
    }

    @Around("@annotation(operationLog)")
    public Object around(ProceedingJoinPoint joinPoint, OperationLog operationLog) throws Throwable {
        long start = System.nanoTime();
        boolean success = true;
        String errorMsg = null;
        try {
            return joinPoint.proceed();
        } catch (Throwable throwable) {
            success = false;
            errorMsg = abbreviate(throwable);
            throw throwable;
        } finally {
            write(joinPoint, operationLog, success, errorMsg, (System.nanoTime() - start) / 1_000_000);
        }
    }

    private void write(ProceedingJoinPoint joinPoint, OperationLog annotation,
                       boolean success, String errorMsg, long costMs) {
        try {
            HttpServletRequest request = WebUtils.currentRequest();
            Caller principal = AuthenticatedPrincipal.currentOrNull();

            SysOperationLog record = new SysOperationLog();
            record.setModule(annotation.module());
            record.setOperation(annotation.operation());
            record.setSuccess(success ? 1 : 0);
            record.setErrorMsg(errorMsg);
            record.setCostMs(costMs);
            record.setCreateTime(LocalDateTime.now());
            record.setTenantId(principal != null ? principal.tenantId() : TenantContext.current());
            record.setOperatorId(principal != null ? principal.subjectId() : null);
            record.setOperatorName(principal != null ? principal.username() : null);
            record.setTraceId(org.slf4j.MDC.get(com.wherelee.cabinet.common.api.R.TRACE_ID_KEY));
            if (request != null) {
                record.setRequestUri(request.getRequestURI());
                record.setRequestMethod(request.getMethod());
                record.setIp(WebUtils.clientIp(request));
            }
            if (annotation.saveParams()) {
                record.setParams(MaskUtils.toMaskedSummary(objectMapper, businessArgs(joinPoint.getArgs())));
            }
            writer.write(record);
        } catch (RuntimeException e) {
            log.error("写审计日志失败 method={}", joinPoint.getSignature().toShortString(), e);
        }
    }

    private static Object businessArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        Object[] filtered = Arrays.stream(args)
                .filter(arg -> !(arg instanceof HttpServletRequest)
                        && !(arg instanceof HttpServletResponse)
                        && !(arg instanceof org.springframework.web.multipart.MultipartFile))
                .toArray();
        if (filtered.length == 0) {
            return null;
        }
        return filtered.length == 1 ? filtered[0] : filtered;
    }

    private static String abbreviate(Throwable throwable) {
        String message = throwable.getClass().getSimpleName()
                + (throwable.getMessage() == null ? "" : ": " + throwable.getMessage());
        return message.length() <= ERROR_MSG_LIMIT ? message : message.substring(0, ERROR_MSG_LIMIT) + "...";
    }
}
