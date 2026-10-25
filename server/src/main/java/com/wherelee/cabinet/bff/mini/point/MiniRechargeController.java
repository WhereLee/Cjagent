package com.wherelee.cabinet.bff.mini.point;

import com.wherelee.cabinet.funds.api.RechargePort;
import com.wherelee.cabinet.funds.api.RechargeView;
import com.wherelee.cabinet.common.annotation.Idempotent;
import com.wherelee.cabinet.common.annotation.OperationLog;
import com.wherelee.cabinet.common.annotation.RateLimit;
import com.wherelee.cabinet.common.api.R;
import com.wherelee.cabinet.platform.security.Caller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "用户端-充值")
@RestController
@RequestMapping("/api/mini/points")
@Validated
public class MiniRechargeController {

    private final RechargePort recharge;

    public MiniRechargeController(RechargePort recharge) {
        this.recharge = recharge;
    }

    public record RechargeCommand(@NotBlank @Size(max = 64) String requestId,
                                  @NotNull @Positive Long points) {
    }

    @Operation(summary = "充值点数",
            description = "走 PayChannel 收单（当前 MOCK）；通道未确认收款不入账，重复提交按幂等返回")
    @OperationLog(module = "point", operation = "充值")
    @RateLimit(limit = 10, windowSeconds = 60, message = "充值过于频繁，请稍后再试")
    @Idempotent(key = "#cmd.requestId", requireKey = true, message = "该充值请求已在处理，请勿重复提交")
    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping("/recharge")
    public R<RechargeView> recharge(@AuthenticationPrincipal Caller current,
                                    @Valid @RequestBody RechargeCommand cmd) {
        return R.ok(recharge.recharge(current.subjectId(), cmd.points()));
    }

}
