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
    /**
     * 无可用格口：包了尺寸/柜机维度的真实缺货。
     *
     * <p>与 {@link #SLOT_RACE_LOST} 必须分开：“没货”重试无用，“抢输了”重试有机会。
     * 混成一个码，压测报告里就分不清“库存不足”与“锁竞争损耗”，而后者才是可优化的。
     */
    SLOT_UNAVAILABLE(10409, "该尺寸暂无空闲格口"),
    /** 候选格口在分配瞬间被他人占走（CAS 失败）：属可重试冲突，不是库存问题 */
    SLOT_RACE_LOST(10410, "格口刚刚被占用，请重试"),
    /**
     * 点数不足（冻结或消耗时条件 UPDATE 影响 0 行）。
     *
     * <p>它必须是<b>业务码而不是系统错</b>：并发下“余额不足”是条件更新的正常分支，
     * 把它报成 5xxxx 会把正常的用户行为计进服务告警，真正的故障反而淹没在噪声里。
     */
    POINT_INSUFFICIENT(10412, "点数不足，请先充值"),

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
