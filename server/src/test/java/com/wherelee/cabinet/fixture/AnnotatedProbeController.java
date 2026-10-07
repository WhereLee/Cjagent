package com.wherelee.cabinet.fixture;

import com.wherelee.cabinet.common.annotation.Idempotent;
import com.wherelee.cabinet.common.annotation.OperationLog;
import com.wherelee.cabinet.common.annotation.RateLimit;
import com.wherelee.cabinet.common.api.R;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 切面验证探针（只在测试源码里）：给三个注解各留一个可调用入口。
 *
 * <p>挂在 {@code /api/public/**} 下，属于 public 安全链，测试不必先登录就能专攻切面本身；
 * 带登录态的审计字段（operatorId/tenantId）由 AdminAuthFlowTest 的真实登录路径覆盖。
 */
@RestController
@RequestMapping("/api/public/probe")
public class AnnotatedProbeController {

    /** 审计 + 脱敏：body 里带 phone/password，落库摘要必须已遮蔽。 */
    public record ProbeBody(
            @NotBlank(message = "orderNo 不能为空") String orderNo,
            String phone,
            String password) {
    }

    @OperationLog(module = "probe", operation = "审计探针")
    @PostMapping("/audit")
    public R<String> audit(@Valid @RequestBody ProbeBody body) {
        return R.ok("ok:" + body.orderNo());
    }

    /** 失败也要留痕：抛业务异常，审计的 success 应为 0 且 error_msg 非空。 */
    @OperationLog(module = "probe", operation = "审计探针-失败")
    @PostMapping("/audit-failing")
    public R<String> auditFailing(@RequestBody ProbeBody body) {
        throw new BizException(ResultCode.BIZ_ERROR, "故意失败: " + body.orderNo());
    }

    /** 不记入参：saveParams=false 时 params 必须为 null（敏感载荷接口用）。 */
    @OperationLog(module = "probe", operation = "审计探针-不记参数", saveParams = false)
    @PostMapping("/audit-no-params")
    public R<String> auditNoParams(@RequestBody ProbeBody body) {
        return R.ok("ok");
    }

    @Idempotent(key = "#body.orderNo()", requireKey = true, ttlSeconds = 60)
    @PostMapping("/idempotent")
    public R<String> idempotent(@Valid @RequestBody ProbeBody body) {
        return R.ok("executed:" + body.orderNo());
    }

    /** 兜底键（不写 key）：同一用户+接口+入参相同才算重复。 */
    @Idempotent(ttlSeconds = 60)
    @PostMapping("/idempotent-fallback")
    public R<String> idempotentFallback(@RequestBody ProbeBody body) {
        return R.ok("executed");
    }

    /** 业务失败要释放占位：第一次失败后，同键第二次仍应可执行。 */
    @Idempotent(key = "#body.orderNo()", requireKey = true, ttlSeconds = 60)
    @PostMapping("/idempotent-failing")
    public R<String> idempotentFailing(@Valid @RequestBody ProbeBody body) {
        throw new BizException(ResultCode.BIZ_ERROR, "故意失败");
    }

    @RateLimit(key = "probe-global", limit = 3, windowSeconds = 60, dimension = RateLimit.Dimension.GLOBAL)
    @GetMapping("/limited")
    public R<String> limited() {
        return R.ok("ok");
    }
}
