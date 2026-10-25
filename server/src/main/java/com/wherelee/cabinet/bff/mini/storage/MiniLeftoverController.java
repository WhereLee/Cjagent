package com.wherelee.cabinet.bff.mini.storage;

import com.wherelee.cabinet.storage.api.LeftoverPort;
import com.wherelee.cabinet.common.annotation.Idempotent;
import com.wherelee.cabinet.common.annotation.OperationLog;
import com.wherelee.cabinet.common.annotation.RateLimit;
import com.wherelee.cabinet.common.api.R;
import com.wherelee.cabinet.platform.security.Caller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "客户-遗留物取回", description = "上一位落了东西在柜里：物主凭登录态自助取回")
@RestController
@RequestMapping("/api/mini/storage/orders/{orderNo}/leftover")
@Validated
public class MiniLeftoverController {

    private final LeftoverPort retrievals;

    public MiniLeftoverController(LeftoverPort retrievals) {
        this.retrievals = retrievals;
    }

    @Operation(summary = "开门取物",
            description = "三重校验（自己的终态单 + 格口仍是遗留物状态 + 格口未被占用）后下发开门；返回话术提示下一步")
    @OperationLog(module = "storage", operation = "遗留物取回开门")
    @RateLimit(limit = 6, windowSeconds = 60, message = "操作过于频繁，请稍后再试")
    @Idempotent(message = "开门请求处理中，请勿重复操作")
    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping("/open")
    public R<String> open(@AuthenticationPrincipal Caller current,
                          @PathVariable @NotBlank @Size(max = 32) String orderNo) {
        return R.ok(retrievals.openForRetrieval(current.subjectId(), orderNo));
    }

    @Operation(summary = "我取完了",
            description = "关门后确认：复检柜内无物则格口自动恢复使用；仍有物则保持锁定并提示继续检查")
    @OperationLog(module = "storage", operation = "遗留物取回确认")
    @RateLimit(limit = 6, windowSeconds = 60, message = "操作过于频繁，请稍后再试")
    @Idempotent(message = "确认处理中，请勿重复提交")
    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping("/taken")
    public R<String> taken(@AuthenticationPrincipal Caller current,
                           @PathVariable @NotBlank @Size(max = 32) String orderNo) {
        return R.ok(retrievals.confirmRetrieved(current.subjectId(), orderNo));
    }
}
