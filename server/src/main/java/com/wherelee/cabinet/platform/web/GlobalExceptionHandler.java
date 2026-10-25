package com.wherelee.cabinet.platform.web;

import com.wherelee.cabinet.common.api.R;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
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

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @Value("${cabinet.exception.echo-detail:false}")
    private boolean echoDetail;

    @ExceptionHandler(BizException.class)
    public ResponseEntity<R<Void>> handleBiz(BizException e, HttpServletRequest req) {
        log.warn("业务异常 {} {} code={} msg={}", req.getMethod(), req.getRequestURI(),
                e.getResultCode().getCode(), e.getMessage());
        return ResponseEntity.status(httpStatusFor(e.getResultCode()))
                .body(R.fail(e.getResultCode(), e.getMessage()));
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<R<Void>> handleAccessDenied(org.springframework.security.access.AccessDeniedException e,
                                                     HttpServletRequest req) {
        log.warn("权限不足 {} {}：{}", req.getMethod(), req.getRequestURI(), e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(R.fail(ResultCode.FORBIDDEN));
    }

    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    public ResponseEntity<R<Void>> handleAuthentication(org.springframework.security.core.AuthenticationException e,
                                                       HttpServletRequest req) {
        log.warn("认证失败 {} {}：{}", req.getMethod(), req.getRequestURI(), e.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(R.fail(ResultCode.UNAUTHORIZED));
    }

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

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<R<Void>> handleInvalidBody(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::formatFieldError)
                .collect(Collectors.joining("; "));
        log.warn("参数校验失败: {}", detail);
        return ResponseEntity.badRequest().body(R.fail(ResultCode.PARAM_INVALID, detail));
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<R<Void>> handleBind(BindException e) {
        String detail = e.getFieldErrors().stream()
                .map(GlobalExceptionHandler::formatFieldError)
                .collect(Collectors.joining("; "));
        log.warn("参数绑定失败: {}", detail);
        return ResponseEntity.badRequest().body(R.fail(ResultCode.PARAM_INVALID, detail));
    }

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

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<R<Void>> handleUnsupportedMediaType(
            org.springframework.web.HttpMediaTypeNotSupportedException e) {
        log.warn("请求体类型不支持：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(R.fail(ResultCode.PARAM_INVALID,
                        echoDetail ? e.getMessage() : "请求体 Content-Type 不支持，应使用 application/json"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<R<Void>> handleNotFound(NoResourceFoundException e) {
        log.warn("资源不存在: {}", e.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(R.fail(ResultCode.RESOURCE_NOT_FOUND));
    }

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
        for (int depth = 0; cursor != null && depth < 10; depth++, cursor = cursor.getCause()) {
            if (cursor instanceof BizException biz) {
                return biz;
            }
        }
        return null;
    }

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
