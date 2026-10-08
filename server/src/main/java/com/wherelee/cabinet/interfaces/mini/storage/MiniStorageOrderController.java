package com.wherelee.cabinet.interfaces.mini.storage;

import com.wherelee.cabinet.application.storage.StorageOrderFacade;
import com.wherelee.cabinet.application.storage.dto.CreateOrderCommand;
import com.wherelee.cabinet.application.storage.dto.StorageOrderView;
import com.wherelee.cabinet.common.annotation.Idempotent;
import com.wherelee.cabinet.common.annotation.OperationLog;
import com.wherelee.cabinet.common.annotation.RateLimit;
import com.wherelee.cabinet.common.api.R;
import com.wherelee.cabinet.infrastructure.security.VerifiedToken;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户端寄存接口（本刀只做占位与取消，投件确认/开柜/结算在后续刀接入）。
 *
 * <p>三个注解不是装饰，各挡一类真实事故：
 * <ul>
 *   <li>{@code @PreAuthorize}：客户身份 + 冻结即 403（服务端判定，不靠前端隐藏按钮）</li>
 *   <li>{@code @Idempotent}：弱网重复点击不能产生两张单、占两个格口；
 *       {@code requireKey = true} 强制业务键真的传上来</li>
 *   <li>{@code @RateLimit}：防脚本刷单——下单会占住稀缺格口，
 *       不限流等于让一个人把整柜锁空</li>
 * </ul>
 */
@Tag(name = "用户端-寄存")
@RestController
@RequestMapping("/api/mini/storage/orders")
@Validated
public class MiniStorageOrderController {

    private final StorageOrderFacade storageOrderFacade;

    public MiniStorageOrderController(StorageOrderFacade storageOrderFacade) {
        this.storageOrderFacade = storageOrderFacade;
    }

    @Operation(summary = "占位（选格口）", description = "并发下抢到格口才建单；失败区分 10409 无位 / 10410 抢输")
    @OperationLog(module = "storage", operation = "寄存占位")
    @RateLimit(limit = 20, windowSeconds = 60, message = "操作过于频繁，请稍后再试")
    @Idempotent(key = "#cmd.requestId", requireKey = true, message = "该请求已处理，请勿重复提交")
    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping
    public R<StorageOrderView> create(@AuthenticationPrincipal VerifiedToken current,
                                      @Valid @RequestBody CreateOrderCommand cmd) {
        return R.ok(storageOrderFacade.create(current.subjectId(), cmd));
    }

    @Operation(summary = "取消占位", description = "免费取消窗口内全额释放；格口随事务释放")
    @OperationLog(module = "storage", operation = "寄存取消")
    @RateLimit(limit = 30, windowSeconds = 60)
    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping("/{orderNo}/cancel")
    public R<StorageOrderView> cancel(@AuthenticationPrincipal VerifiedToken current,
                                      @PathVariable @NotBlank @Size(max = 32) String orderNo) {
        return R.ok(storageOrderFacade.cancel(current.subjectId(), orderNo));
    }
}
