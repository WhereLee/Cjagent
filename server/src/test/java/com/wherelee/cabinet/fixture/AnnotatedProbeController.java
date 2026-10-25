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

@RestController
@RequestMapping("/api/public/probe")
public class AnnotatedProbeController {

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

    @OperationLog(module = "probe", operation = "审计探针-失败")
    @PostMapping("/audit-failing")
    public R<String> auditFailing(@RequestBody ProbeBody body) {
        throw new BizException(ResultCode.BIZ_ERROR, "故意失败: " + body.orderNo());
    }

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

    @Idempotent(ttlSeconds = 60)
    @PostMapping("/idempotent-fallback")
    public R<String> idempotentFallback(@RequestBody ProbeBody body) {
        return R.ok("executed");
    }

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
