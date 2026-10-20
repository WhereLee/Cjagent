package com.wherelee.cabinet.interfaces.mini.storage;

import com.wherelee.cabinet.application.storage.StorageOrderFacade;
import org.springframework.web.bind.annotation.RequestParam;
import com.wherelee.cabinet.application.storage.dto.CreateOrderCommand;
import com.wherelee.cabinet.application.storage.dto.StorageOrderView;
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

    /**
     * 取消入口已去掉（2026-10-10 定-2/定-3）：下单与开门合并后不存在“下了单没开门”这个用户可停留的状态，
     * 开门失败服服当场退回；“开了柜但没放东西”走正常的结束流程（在免费窗口内就是 0 元）。
     * 服务层的 cancel 保留：它是异常退回与待收敛回收的实现手段，不再对外提供。
     */

    /**
     * 开柜类动作。
     *
     * <p>两层幂等故意分开：这里用切面默认的“用户+URI+入参摘要”防**双击**；
     * 设备侧真正的“不重复下发”由 {@code DeviceCommandService} 按 attempt 生成的 requestId 保证。
     * 只靠前者会误伤正常重试（同一 URI 重发被当成重复），只靠后者则拦不住“同一次尝试发两次”。
     */
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

    /**
     * 取件：结算 + 释放格口（可带取件码，见下方重载）。
     *
     * <p>没挂 @Idempotent：它是“按单号结算”，本身就是幂等的（重复到达会因
     * 状态已 CLOSED 而被状态机拒绝），再加一层幂等键只会把正常的“再查一次”也拦掉。
     */
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

    /**
     * 取件入参。<b>码可以不填</b>：主路径是登录态（他在自己手机上点结束）；
     * 柜机上输码取件（手机没电时的第二条路径）会带这个字段过来。
     */
    public record PickupRequest(@Size(max = 16) String voucherCode) {
    }

    /**
     * 重置取件码（第 25 刀，用户 2026-10-14 拍板）：凭据自救与锁定出路。
     *
     * <p>身份只来自登录态：锁定防的是猜码的路人，本人设备上的重置不伤害这件事。
     * 限流比开柜更严：换码不应该被脚本拿着扫。
     */
    @Operation(summary = "重置取件码",
            description = "换码后旧码立即失效、错次清零、连错锁定一并解除；"
                    + "凭证消息改写为当前码（不再发第二条）")
    @OperationLog(module = "storage", operation = "重置取件码")
    @RateLimit(limit = 6, windowSeconds = 60, message = "重置太频繁，请稍后再试")
    @Idempotent(message = "重置处理中，请勿重复提交")
    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping("/{orderNo}/voucher/reset")
    public R<com.wherelee.cabinet.application.storage.dto.VoucherView> resetVoucher(
            @AuthenticationPrincipal Caller current,
            @PathVariable @NotBlank @Size(max = 32) String orderNo) {
        return R.ok(storageOrderFacade.resetVoucher(current.subjectId(), orderNo));
    }

    /**
     * 上报“这个格口里有别人的东西”（设计中的发现者路径）。
     *
     * <p>上报后本单不收费退回：要他为一个不是自己造成的现场付钱，得到的只会是下次直接走人不报。
     */
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
