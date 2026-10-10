package com.wherelee.cabinet.interfaces.mini.point;

import com.wherelee.cabinet.application.point.RechargeService;
import com.wherelee.cabinet.application.point.dto.RechargeView;
import com.wherelee.cabinet.common.annotation.Idempotent;
import com.wherelee.cabinet.common.annotation.OperationLog;
import com.wherelee.cabinet.common.annotation.RateLimit;
import com.wherelee.cabinet.common.api.R;
import com.wherelee.cabinet.infrastructure.security.VerifiedToken;
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

/**
 * 用户端充值接口。
 *
 * <p>它补的是一个真实存在的洞：第 12 刀只有内部 {@code recharge()} 调用，dev 环境新用户
 * 一上线就是 0 点，任何下单都被"点数不足"挡死——<b>没有入口的资金系统等于没有资金系统</b>。
 *
 * <p>{@code @Idempotent(requireKey = true)} 在这里不是防双击那么轻：充值是<b>加钱</b>的动作，
 * 弱网重发如果没有业务键，服务端看到的就是两笔独立请求，只能各收一次、各入一次。
 * 真正兜住这件事的是通道侧幂等（服务端生成的 outTradeNo + 唯一键 + 条件更新），
 * 这一层只是把无谓的第二次下单挡在前面。
 *
 * @param cmd 请求体：requestId 由客户端生成（本次尝试的唯一标识）
 */
@Tag(name = "用户端-充值")
@RestController
@RequestMapping("/api/mini/points")
@Validated
public class MiniRechargeController {

    private final RechargeService recharge;
    private final com.wherelee.cabinet.application.point.OrderFundService funds;

    public MiniRechargeController(RechargeService recharge,
                                  com.wherelee.cabinet.application.point.OrderFundService funds) {
        this.recharge = recharge;
        this.funds = funds;
    }

    /** @param points 购买点数（1 点 = 1 分，S-01） */
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
    public R<RechargeView> recharge(@AuthenticationPrincipal VerifiedToken current,
                                    @Valid @RequestBody RechargeCommand cmd) {
        return R.ok(recharge.recharge(current.subjectId(), cmd.points()));
    }

    /**
     * 自助退押金（2026-10-10 定：由客户自己发起，不需客服）。
     *
     * <p>返回的是真正回到可用余额的点数（押金 - 被抵掉的欠款）。退押不会把欠单一笔勾销：
     * 押金不够抵时剩下来的欠款依旧挂着，依旧拦住下一次下单。
     */
    @Operation(summary = "退账户押金", description = "先用押金抵欠款，剩下退回可用余额；真实原路退回待接入支付通道")
    @OperationLog(module = "point", operation = "退押金")
    @RateLimit(limit = 5, windowSeconds = 60, message = "退押金过于频繁，请稍后再试")
    @Idempotent(key = "#cmd.requestId", requireKey = true, message = "该退押金请求已在处理")
    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping("/deposit/refund")
    public R<Long> refundDeposit(@AuthenticationPrincipal VerifiedToken current,
                                 @Valid @RequestBody RefundRequest cmd) {
        return R.ok(funds.refundDeposit(current.subjectId(), cmd.requestId()));
    }

    /** 退押金也要业务键：它会把钱放回可花余额，不是一个可以重放的读接口。 */
    public record RefundRequest(@NotBlank @Size(max = 64) String requestId) {
    }
}
