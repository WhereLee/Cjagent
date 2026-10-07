package com.wherelee.cabinet.common.api;

import org.slf4j.MDC;

import java.time.OffsetDateTime;

/**
 * 统一响应体。所有对外接口（含异常）都以本结构返回，前端只需处理 code / message。
 *
 * <p>traceId 由 {@code TraceIdFilter} 写入 MDC，这里自动带出，便于用户报障时直接定位日志。
 *
 * @param <T> 业务数据类型
 */
public class R<T> {

    public static final String TRACE_ID_KEY = "traceId";

    private int code;
    private String message;
    private T data;
    private String traceId;
    private OffsetDateTime timestamp;

    private R() {
        this.timestamp = OffsetDateTime.now();
        this.traceId = MDC.get(TRACE_ID_KEY);
    }

    public static <T> R<T> ok(T data) {
        R<T> r = new R<>();
        r.code = ResultCode.SUCCESS.getCode();
        r.message = ResultCode.SUCCESS.getMessage();
        r.data = data;
        return r;
    }

    public static <T> R<T> ok() {
        return ok(null);
    }

    public static <T> R<T> fail(ResultCode resultCode) {
        return fail(resultCode, resultCode.getMessage());
    }

    public static <T> R<T> fail(ResultCode resultCode, String message) {
        R<T> r = new R<>();
        r.code = resultCode.getCode();
        r.message = message;
        return r;
    }

    public static <T> R<T> fail(int code, String message) {
        R<T> r = new R<>();
        r.code = code;
        r.message = message;
        return r;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public OffsetDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(OffsetDateTime timestamp) {
        this.timestamp = timestamp;
    }
}
