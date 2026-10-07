package com.wherelee.cabinet.common.exception;

import com.wherelee.cabinet.common.api.R;
import com.wherelee.cabinet.common.api.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理：把各类异常收敛成统一响应体，并保证 HTTP 状态码与错误码语义一致。
 *
 * <p>分级策略：
 * <ul>
 *   <li>业务异常 → warn，无堆栈（可预期，不是故障）</li>
 *   <li>参数/路由类异常 → warn，带请求路径，便于前端排查</li>
 *   <li>未知异常 → error + 完整堆栈，对外只暴露 SYSTEM_ERROR，绝不下泄内部信息</li>
 * </ul>
 *
 * <p>{@code cabinet.exception.echo-detail}：dev 环境把真实异常信息回显给前端，方便联调；
 * prod 必须为 false，只返回标准错误码。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Value("${cabinet.exception.echo-detail:false}")
    private boolean echoDetail;

    /**
     * 业务异常：按错误码族决定 HTTP 状态（见 {@link #httpStatusFor}）。
     */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<R<Void>> handleBiz(BizException e, HttpServletRequest req) {
        log.warn("业务异常 {} {} code={} msg={}", req.getMethod(), req.getRequestURI(),
                e.getResultCode().getCode(), e.getMessage());
        return ResponseEntity.status(httpStatusFor(e.getResultCode()))
                .body(R.fail(e.getResultCode(), e.getMessage()));
    }

    /**
     * 方法级权限判定（{@code @PreAuthorize}）拒绝。
     *
     * <p><b>必须显式列出</b>：它会被下面的 {@code Exception} 兜底先抢走，变成 500 + 50000。
     * 后果不只是状态码错：前端把 50000 当"系统异常、稍后重试"提示，用户反复重试还是不行；
     * 而服务端告警也会被这种可预期的拒绝弄成噪声。
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<R<Void>> handleAccessDenied(org.springframework.security.access.AccessDeniedException e,
                                                     HttpServletRequest req) {
        log.warn("权限不足 {} {}：{}", req.getMethod(), req.getRequestURI(), e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(R.fail(ResultCode.FORBIDDEN));
    }

    /** 认证失败（非过滤器链路径，比如凭证解析到一半出错）。 */
    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    public ResponseEntity<R<Void>> handleAuthentication(org.springframework.security.core.AuthenticationException e,
                                                       HttpServletRequest req) {
        log.warn("认证失败 {} {}：{}", req.getMethod(), req.getRequestURI(), e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(R.fail(ResultCode.UNAUTHORIZED));
    }

    /**
     * 错误码族 → HTTP 状态。
     *
     * <p>约定记在 docs/架构约定.md §3：{@code 1xxxx} 业务错误用 HTTP 200（请求本身处理完了，
     * 只是结果不满足）；{@code 4xxxx} 客户端错误用对应状态码；{@code 5xxxx} 用 500。
     * 不把 40100/40300 返成 200 是为了前端能区分“去登录”与“提示重试”。
     */
    private static HttpStatus httpStatusFor(ResultCode code) {
        int value = code.getCode();
        if (value >= 50000) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        if (value >= 40000 && value < 50000) {
            return switch (value) {
                case 40100 -> HttpStatus.UNAUTHORIZED;
                case 40300, 40301 -> HttpStatus.FORBIDDEN;
                case 40400 -> HttpStatus.NOT_FOUND;
                case 40500 -> HttpStatus.METHOD_NOT_ALLOWED;
                case 40900 -> HttpStatus.CONFLICT;
                case 42900 -> HttpStatus.TOO_MANY_REQUESTS;
                default -> HttpStatus.BAD_REQUEST;
            };
        }
        return HttpStatus.OK;
    }

    /** @Valid 校验失败（RequestBody）。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Void>> handleInvalidBody(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::formatFieldError)
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", detail);
        return ResponseEntity.badRequest().body(R.fail(ResultCode.PARAM_INVALID, detail));
    }

    /** 表单/Query 对象绑定校验失败。 */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<R<Void>> handleBind(BindException e) {
        String detail = e.getFieldErrors().stream()
                .map(GlobalExceptionHandler::formatFieldError)
                .collect(Collectors.joining("; "));
        log.warn("参数绑定失败: {}", detail);
        return ResponseEntity.badRequest().body(R.fail(ResultCode.PARAM_INVALID, detail));
    }

    /** 方法参数上的 @NotNull/@Min 等约束失败（@Validated 在 Controller 类上）。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<R<Void>> handleConstraint(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .collect(Collectors.joining("; "));
        log.warn("约束校验失败: {}", detail);
        return ResponseEntity.badRequest().body(R.fail(ResultCode.PARAM_INVALID, detail));
    }

    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<R<Void>> handleBadRequest(Exception e) {
        log.warn("请求格式错误: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(R.fail(ResultCode.PARAM_INVALID, echoDetail ? e.getMessage() : "请求参数格式不正确"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<R<Void>> handleMethod(HttpRequestMethodNotSupportedException e) {
        log.warn("请求方法不支持: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(R.fail(ResultCode.METHOD_NOT_ALLOWED));
    }

    /** 未匹配到任何路由（Boot 3.2+ 抛此异常），不当成系统故障记录。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<R<Void>> handleNotFound(NoResourceFoundException e) {
        log.warn("资源不存在: {}", e.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(R.fail(ResultCode.RESOURCE_NOT_FOUND));
    }

    /**
     * 数据访问异常：先剥包装找业务异常，否则统一当系统异常。
     *
     * <p>为什么必须多这一步：MyBatis-Spring 会把底层异常转译成 DataAccessException，
     * 租户守卫抛的 BizException（40301）就被埋在 cause 里。不剥的话，
     * “缺租户上下文”这种可预期的使用错误会被当成 500 系统异常报出去，
     * 既误导了前端处理（401/403 类提示 vs 稍后重试），也把告警噪声拉高。
     */
    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<R<Void>> handleDataAccess(org.springframework.dao.DataAccessException e,
                                                   HttpServletRequest req) {
        BizException biz = unwrapBizException(e);
        if (biz != null) {
            log.warn("数据层包装的业务异常 {} {}: code={} msg={}", req.getMethod(), req.getRequestURI(),
                    biz.getResultCode().getCode(), biz.getMessage());
            return ResponseEntity.status(httpStatusFor(biz.getResultCode()))
                    .body(R.fail(biz.getResultCode(), biz.getMessage()));
        }
        log.error("数据访问异常 {} {}", req.getMethod(), req.getRequestURI(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(R.fail(ResultCode.MIDDLEWARE_UNAVAILABLE,
                        echoDetail ? e.getMessage() : ResultCode.MIDDLEWARE_UNAVAILABLE.getMessage()));
    }

    private static BizException unwrapBizException(Throwable e) {
        Throwable cursor = e.getCause();
        // 限深遍历，避免自引用异常链造成死循环
        for (int depth = 0; cursor != null && depth < 10; depth++, cursor = cursor.getCause()) {
            if (cursor instanceof BizException biz) {
                return biz;
            }
        }
        return null;
    }

    /** 兜底：未知异常一定留完整堆栈，对外信息按环境决定是否回显。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<R<Void>> handleUnknown(Exception e, HttpServletRequest req) {
        log.error("系统异常 {} {}", req.getMethod(), req.getRequestURI(), e);
        String message = echoDetail ? e.getClass().getSimpleName() + ": " + e.getMessage()
                : ResultCode.SYSTEM_ERROR.getMessage();
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(R.fail(ResultCode.SYSTEM_ERROR, message));
    }

    private static String formatFieldError(FieldError fe) {
        return fe.getField() + " " + fe.getDefaultMessage();
    }
}
