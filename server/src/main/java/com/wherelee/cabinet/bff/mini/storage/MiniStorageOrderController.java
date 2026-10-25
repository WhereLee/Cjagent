package com.wherelee.cabinet.bff.mini.storage;

import com.wherelee.cabinet.storage.api.StorageOrderCommandPort;
import org.springframework.web.bind.annotation.RequestParam;
import com.wherelee.cabinet.storage.api.CreateOrderCommand;
import com.wherelee.cabinet.storage.api.StorageOrderView;
import com.wherelee.cabinet.common.annotation.Idempotent;
import com.wherelee.cabinet.common.annotation.OperationLog;
import com.wherelee.cabinet.common.annotation.RateLimit;
import com.wherelee.cabinet.common.api.R;
import com.wherelee.cabinet.platform.security.Caller;
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

@Tag(name = "用户端-寄存")
@RestController
@RequestMapping("/api/mini/storage/orders")
@Validated
public class MiniStorageOrderController {

    private final StorageOrderCommandPort storageOrderFacade;

    public MiniStorageOrderController(StorageOrderCommandPort storageOrderFacade) {
        this.storageOrderFacade = storageOrderFacade;
    }

    @Operation(summary = "存件（下单并开门）",
            description = "一步完成：占格 + 预收 4 小时费用 + 预备开握手后下发开门，返回格口号；10409 无位 / 10410 抢输")
    @OperationLog(module = "storage", operation = "存件下单")
    @RateLimit(limit = 20, windowSeconds = 60, message = "操作过于频繁，请稍后再试")
    @Idempotent(key = "#cmd.requestId", requireKey = true, message = "该请求已处理，请勿重复提交")
    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping
    public R<StorageOrderView> create(@AuthenticationPrincipal Caller current,
                                      @Valid @RequestBody CreateOrderCommand cmd) {
        return R.ok(storageOrderFacade.place(current.subjectId(), cmd));
    }

    @Operation(summary = "开柜/关门校验",
            description = "action：OPEN（投件开柜）| OPEN_TEMP（中途取物）| CLOSE_VERIFY（关门校验）")
    @OperationLog(module = "storage", operation = "开柜")
    @RateLimit(limit = 12, windowSeconds = 60, message = "开柜操作过于频繁，请稍候再试")
    @Idempotent(message = "开柜请求处理中，请勿重复操作")
    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping("/{orderNo}/door")
    public R<StorageOrderView> door(@AuthenticationPrincipal Caller current,
                                    @PathVariable @NotBlank @Size(max = 32) String orderNo,
                                    @RequestParam @NotBlank @Size(max = 16) String action) {
        return R.ok(storageOrderFacade.openDoor(current.subjectId(), orderNo,
                com.wherelee.cabinet.device.api.CommandAction.of(action)));
    }

    @Operation(summary = "取件结算",
            description = "一律不拦人（定-17）：按实际时长结算、预收多退少补；门没关或柜内有物也照常结束，只记异常与证据。可带取件码做二次校验（连错 5 次锁定该单开柜）")
    @OperationLog(module = "storage", operation = "取件结算")
    @RateLimit(limit = 12, windowSeconds = 60)
    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping("/{orderNo}/pickup")
    public R<StorageOrderView> pickup(@AuthenticationPrincipal Caller current,
                                      @PathVariable @NotBlank @Size(max = 32) String orderNo,
                                      @RequestBody(required = false) PickupRequest request) {
        return R.ok(storageOrderFacade.pickup(current.subjectId(), orderNo,
                request == null ? null : request.voucherCode()));
    }

    public record PickupRequest(@Size(max = 16) String voucherCode) {
    }

    @Operation(summary = "重置取件码",
            description = "换码后旧码立即失效、错次清零、连错锁定一并解除；"
                    + "凭证消息改写为当前码（不再发第二条）")
    @OperationLog(module = "storage", operation = "重置取件码")
    @RateLimit(limit = 6, windowSeconds = 60, message = "重置太频繁，请稍后再试")
    @Idempotent(message = "重置处理中，请勿重复提交")
    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping("/{orderNo}/voucher/reset")
    public R<com.wherelee.cabinet.storage.api.VoucherView> resetVoucher(
            @AuthenticationPrincipal Caller current,
            @PathVariable @NotBlank @Size(max = 32) String orderNo) {
        return R.ok(storageOrderFacade.resetVoucher(current.subjectId(), orderNo));
    }

    @Operation(summary = "上报格口内有他人遗留物",
            description = "锁定该格口（不再分配）并免费退回自己这一单，同时通知原主与运维")
    @OperationLog(module = "storage", operation = "上报遗留物")
    @RateLimit(limit = 6, windowSeconds = 60)
    @Idempotent(message = "上报处理中，请勿重复提交")
    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping("/{orderNo}/report-leftover")
    public R<StorageOrderView> reportLeftover(@AuthenticationPrincipal Caller current,
                                             @PathVariable @NotBlank @Size(max = 32) String orderNo) {
        return R.ok(storageOrderFacade.reportLeftover(current.subjectId(), orderNo));
    }
}
