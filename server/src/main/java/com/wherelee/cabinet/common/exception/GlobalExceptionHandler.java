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

    /** 业务异常：HTTP 200 + 业务码，前端按 code 分支处理。 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<R<Void>> handleBiz(BizException e, HttpServletRequest req) {
        log.warn("业务异常 {} {} code={} msg={}", req.getMethod(), req.getRequestURI(),
                e.getResultCode().getCode(), e.getMessage());
        return ResponseEntity.ok(R.fail(e.getResultCode(), e.getMessage()));
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
