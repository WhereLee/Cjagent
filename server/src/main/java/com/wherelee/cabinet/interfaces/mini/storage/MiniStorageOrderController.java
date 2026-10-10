package com.wherelee.cabinet.interfaces.mini.storage;

import com.wherelee.cabinet.application.storage.StorageOrderFacade;
import org.springframework.web.bind.annotation.RequestParam;
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
    public R<StorageOrderView> door(@AuthenticationPrincipal VerifiedToken current,
                                    @PathVariable @NotBlank @Size(max = 32) String orderNo,
                                    @RequestParam @NotBlank @Size(max = 16) String action) {
        return R.ok(storageOrderFacade.openDoor(current.subjectId(), orderNo,
                com.wherelee.cabinet.domain.enums.CommandAction.of(action)));
    }

    /**
     * 取件：结算 + 释放格口（可带取件码，见下方重载）。
     *
     * <p>没挂 @Idempotent：它是“按单号结算”，本身就是幂等的（重复到达会因
     * 状态已 CLOSED 而被状态机拒绝），再加一层幂等键只会把正常的“再查一次”也拦掉。
     */
    @Operation(summary = "取件结算",
            description = "需同时满足“门已关 + 柜内无物”才停计费；可带取件码（柜机输码路径），连错 5 次锁定该单开柜")
    @OperationLog(module = "storage", operation = "取件结算")
    @RateLimit(limit = 12, windowSeconds = 60)
    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping("/{orderNo}/pickup")
    public R<StorageOrderView> pickup(@AuthenticationPrincipal VerifiedToken current,
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
     * 柜内有物品却确定不要了，声明放弃并结束。
     *
     * <p>它是“柜内检测到物品”时唯一的自助出口，但<b>不代替代关门</b>：门没关就结束，
     * 等于把“开着门、里面有东西、格子还卖得出去”这三件最坏的事同时坐实。
     * 结束后该格口转异常（遗留物），业务运维清走之前不会再分配。
     */
    @Operation(summary = "声明放弃柜内物品并结束",
            description = "仅豁免“柜内无物”，仍要求门已关；结束后格口转待清柜异常")
    @OperationLog(module = "storage", operation = "放弃物品结束")
    @RateLimit(limit = 6, windowSeconds = 60)
    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping("/{orderNo}/abandon")
    public R<StorageOrderView> abandon(@AuthenticationPrincipal VerifiedToken current,
                                       @PathVariable @NotBlank @Size(max = 32) String orderNo) {
        return R.ok(storageOrderFacade.abandon(current.subjectId(), orderNo));
    }

    /**
     * 远程结束订单（人已经离开柜机）。
     *
     * <p>不豁免任何一条结束判据，只额外收一笔“该格口几小时单价”的加收：
     * 如果远程能绕过无物判定，那“离开现场”就比“留在现场”更容易脱身，方向正好反了。
     * 条件不满足时报错并继续计费——这是故意的，文案会告诉用户下一步能做什么。
     */
    @Operation(summary = "远程结束订单",
            description = "同一套结束判据 + 未关门加收；柜内无法确认已清空时会被拒绝")
    @OperationLog(module = "storage", operation = "远程结束订单")
    @RateLimit(limit = 6, windowSeconds = 60)
    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping("/{orderNo}/remote-close")
    public R<StorageOrderView> remoteClose(@AuthenticationPrincipal VerifiedToken current,
                                           @PathVariable @NotBlank @Size(max = 32) String orderNo) {
        return R.ok(storageOrderFacade.remoteClose(current.subjectId(), orderNo));
    }

    /**
     * 否认“柜内有我的东西”→ 触发 AI 看图复审（争议阶梯 L3）。
     *
     * <p>限流给得比开柜紧（6/分钟）：每次复审都是一次 AI 调用，不设频控等于给用户一个可反复按的
     * 烧钱按钮。复审次数用尽后只会得到“已转人工”，不会再发模型调用。
     */
    @Operation(summary = "否认柜内有物品（转 AI 复审）",
            description = "判为设备误报则结束并免除争议期间费用；仍判有物则订单继续计费")
    @OperationLog(module = "storage", operation = "否认柜内物品")
    @RateLimit(limit = 6, windowSeconds = 60)
    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping("/{orderNo}/deny-item")
    public R<StorageOrderView> denyItem(@AuthenticationPrincipal VerifiedToken current,
                                        @PathVariable @NotBlank @Size(max = 32) String orderNo) {
        return R.ok(storageOrderFacade.denyItem(current.subjectId(), orderNo));
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
    public R<StorageOrderView> reportLeftover(@AuthenticationPrincipal VerifiedToken current,
                                             @PathVariable @NotBlank @Size(max = 32) String orderNo) {
        return R.ok(storageOrderFacade.reportLeftover(current.subjectId(), orderNo));
    }
}
