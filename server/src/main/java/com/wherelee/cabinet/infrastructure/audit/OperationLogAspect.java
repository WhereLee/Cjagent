package com.wherelee.cabinet.infrastructure.audit;

import com.wherelee.cabinet.common.annotation.OperationLog;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.common.mask.MaskUtils;
import com.wherelee.cabinet.common.web.WebUtils;
import com.wherelee.cabinet.domain.entity.SysOperationLog;
import com.wherelee.cabinet.infrastructure.security.AuthenticatedPrincipal;
import com.wherelee.cabinet.infrastructure.security.VerifiedToken;
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

/**
 * 操作审计切面：成功与失败都留痕，且<b>绝不改变业务语义</b>。
 *
 * <p>三条硬性要求：
 * <ul>
 *   <li>原异常必须原样抛出（吞掉异常会让 GlobalExceptionHandler 看不到真实故障）；</li>
 *   <li>审计自身异常只记日志不外抛（旁路能力不该打挂正常请求）；</li>
 *   <li>入参摘要必须脱敏+截断（否则审计表成为明文密码聚集地）。</li>
 * </ul>
 *
 * <p>{@code @Order(-200)} 显式设负值，确保它比 Spring Security 的方法级鉴权拦截器更靠外：
 * 否则"权限不足被拒"这类恰恰最该记录的尝试，根本不会进入本切面。
 */
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
            VerifiedToken principal = AuthenticatedPrincipal.currentOrNull();

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
            // 审计写失败不能影响业务；但必须留下痕迹，否则"没有日志"会被误读成"没人调用过"
            log.error("写审计日志失败 method={}", joinPoint.getSignature().toShortString(), e);
        }
    }

    /** 去掉 Servlet 容器对象：它们不可序列化，且把 response 写进审计毫无意义。 */
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

    /** 异常摘要：类型 + 消息，限长；栈信息在应用日志里，不进审计表。 */
    private static String abbreviate(Throwable throwable) {
        String message = throwable.getClass().getSimpleName()
                + (throwable.getMessage() == null ? "" : ": " + throwable.getMessage());
        return message.length() <= ERROR_MSG_LIMIT ? message : message.substring(0, ERROR_MSG_LIMIT) + "...";
    }
}
