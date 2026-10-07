package com.wherelee.cabinet.common.api;

/**
 * 统一错误码。
 *
 * <p>分段约定：0 成功；1xxxx 业务；4xxxx 客户端（参数/认证/权限/限流）；5xxxx 服务端（系统/依赖组件）。
 * 新增错误码只允许在本枚举追加，禁止在业务代码里裸写数字码返回。
 */
public enum ResultCode {

    SUCCESS(0, "成功"),

    BIZ_ERROR(10000, "业务处理失败"),
    DATA_NOT_FOUND(10404, "数据不存在"),

    PARAM_INVALID(40000, "参数校验失败"),
    UNAUTHORIZED(40100, "未认证或登录状态已过期"),
    FORBIDDEN(40300, "无访问权限"),
    TENANT_INVALID(40301, "租户上下文缺失或非法"),
    RESOURCE_NOT_FOUND(40400, "资源不存在"),
    METHOD_NOT_ALLOWED(40500, "请求方法不被支持"),
    IDEMPOTENT_REJECT(40900, "请求重复，请勿重复提交"),
    TOO_MANY_REQUESTS(42900, "请求过于频繁"),

    SYSTEM_ERROR(50000, "系统繁忙，请稍后重试"),
    MIDDLEWARE_UNAVAILABLE(50100, "依赖组件不可用");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
