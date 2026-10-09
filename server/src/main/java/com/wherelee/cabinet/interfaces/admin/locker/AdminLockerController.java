package com.wherelee.cabinet.interfaces.admin.locker;

import com.wherelee.cabinet.application.locker.LockerConsoleService;
import com.wherelee.cabinet.application.storage.AsyncStorageOrderAppService;
import com.wherelee.cabinet.common.annotation.Idempotent;
import com.wherelee.cabinet.common.annotation.OperationLog;
import com.wherelee.cabinet.common.api.PageQuery;
import com.wherelee.cabinet.common.api.PageResult;
import com.wherelee.cabinet.common.api.R;
import com.wherelee.cabinet.infrastructure.security.VerifiedToken;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 后台-储物柜现场运营（第 14 刀）。
 *
 * <p>读接口<b>不加</b> {@code @OperationLog}：只读请求进审计会让表迅速被翻页灌满，
 * 审计噪音一高，真正该看的写操作就没人看了（沿用底座账号查询接口的约定）。
 *
 * <p>权限点<b>逐个分开</b>，没有一个"运营万能权限"：看台账、清格口异常、强制开柜、免除费用、
 * 禁用客户、重建空闲集合是六件事。尤其 {@code locker:order:waive-fee} 是动钱的动作，
 * 必须与"改个状态"的权限不同人可持有。
 */
@Tag(name = "后台-储物柜现场运营")
@RestController
@RequestMapping("/api/admin/locker")
@Validated
public class AdminLockerController {

    private final LockerConsoleService console;
    private final AsyncStorageOrderAppService asyncOrders;

    public AdminLockerController(LockerConsoleService console, AsyncStorageOrderAppService asyncOrders) {
        this.console = console;
        this.asyncOrders = asyncOrders;
    }

    // ------------------------------------------------------------------ 只读

    @Operation(summary = "异常格口台账", description = "默认按判定时刻升序（卡最久的在前）；可按原因码与点位过滤")
    @PreAuthorize("hasAuthority('locker:ledger:list')")
    @GetMapping("/compartments/anomalies")
    public R<PageResult<LockerConsoleService.LedgerView>> anomalies(@Valid PageQuery query,
                                                                     @RequestParam(required = false)
                                                                     @Size(max = 24) String anomaly,
                                                                     @RequestParam(required = false) Long siteId) {
        return R.ok(console.anomalies(query, anomaly, siteId));
    }

    @Operation(summary = "异常积压概览", description = "按原因码给出条数与最长停留分钟，排班看这个数")
    @PreAuthorize("hasAuthority('locker:ledger:list')")
    @GetMapping("/compartments/anomalies/summary")
    public R<List<LockerConsoleService.SummaryView>> anomalySummary(
            @RequestParam(required = false) Long siteId) {
        return R.ok(console.anomalySummary(siteId));
    }

    @Operation(summary = "格口详情", description = "现状 + 证据时间线 + 当前占用的单")
    @PreAuthorize("hasAuthority('locker:ledger:list')")
    @GetMapping("/compartments/{compartmentId}")
    public R<LockerConsoleService.Detail> detail(@PathVariable Long compartmentId) {
        return R.ok(console.compartmentDetail(compartmentId));
    }

    @Operation(summary = "未退押金清单", description = "只列订单已终态而押金凭证仍未退还的（在线单挂着押金是正常态）")
    @PreAuthorize("hasAuthority('locker:deposit:unrefunded-list')")
    @GetMapping("/deposits/unrefunded")
    public R<PageResult<LockerConsoleService.DepositView>> unrefunded(@Valid PageQuery query) {
        return R.ok(console.unrefundedDeposits(query));
    }

    @Operation(summary = "欠费客户清单", description = "按客户汇总未缴点数，降序")
    @PreAuthorize("hasAuthority('locker:arrears:list')")
    @GetMapping("/arrears")
    public R<PageResult<LockerConsoleService.ArrearsView>> arrears(@Valid PageQuery query) {
        return R.ok(console.arrears(query));
    }

    // ------------------------------------------------------------------ 写（逐个权限点）

    @Operation(summary = "清柜：解除格口异常", description = "要求门已关并填写现场说明；说明会进审计与故障流水")
    @OperationLog(module = "locker-console", operation = "清柜解除异常")
    @Idempotent(message = "该处置正在处理中，请勿重复提交")
    @PreAuthorize("hasAuthority('locker:compartment:resolve')")
    @PostMapping("/compartments/{compartmentId}/resolve")
    public R<Void> resolve(@PathVariable Long compartmentId,
                           @RequestBody ResolveRequest request) {
        console.resolveAnomaly(compartmentId, request.note());
        return R.ok();
    }

    @Operation(summary = "后台强制开柜", description = "需二次确认与事由；单已结束后的取回物品只能走这里")
    @OperationLog(module = "locker-console", operation = "强制开柜")
    @Idempotent(message = "开柜指令正在下发，请勿重复确认")
    @PreAuthorize("hasAuthority('locker:door:force-open')")
    @PostMapping("/compartments/{compartmentId}/force-open")
    public R<Void> forceOpen(@AuthenticationPrincipal VerifiedToken current,
                             @PathVariable Long compartmentId,
                             @Valid @RequestBody ForceOpenRequest request) {
        console.forceOpen(compartmentId, request.confirmed(), request.reason(), current.subjectId());
        return R.ok();
    }

    @Operation(summary = "免除争议费用（判定设备误报）",
            description = "金额由算法给出（只算到争议起始时刻），需两条互相打脸的证据；单已终态不可再免")
    @OperationLog(module = "locker-console", operation = "免除争议费用")
    @Idempotent(key = "#orderNo", requireKey = true, message = "该单已处理，请勿重复免除")
    @PreAuthorize("hasAuthority('locker:order:waive-fee')")
    @PostMapping("/orders/{orderNo}/waive-fee")
    public R<Void> waive(@PathVariable @NotBlank @Size(max = 32) String orderNo) {
        console.waiveDisputeFee(orderNo);
        return R.ok();
    }

    @Operation(summary = "重建空闲格口集合", description = "以 DB 真相同步 Redis 准入集合；先 DEL 再 SADD，期间会少卖不会超卖")
    @OperationLog(module = "locker-console", operation = "重建空闲集合")
    @Idempotent(message = "重建正在进行中，请稍候")
    @PreAuthorize("hasAuthority('locker:freeset:rebuild')")
    @PostMapping("/cabinets/{cabinetId}/free-set/rebuild")
    public R<Void> rebuildFreeSet(@PathVariable Long cabinetId) {
        asyncOrders.syncFreeSets(cabinetId);
        return R.ok();
    }

    /** 清柜说明必填：它是"人来现场看过"的唯一凭据。 */
    public record ResolveRequest(@NotBlank @Size(max = 200) String note) {
    }

    /** {@code confirmed} 是二次确认的显式痕迹，避免前端误发一个 POST 就开了别人的柜门。 */
    public record ForceOpenRequest(boolean confirmed, @NotBlank @Size(max = 200) String reason) {
    }

    @Operation(summary = "禁用/启用客户",
            description = "禁用后权限装载为空→登录与下单两侧都 403；只能人工解除，原因进审计")
    @OperationLog(module = "locker-console", operation = "客户禁用/启用")
    @Idempotent(key = "#customerId", requireKey = true, message = "该处置正在处理中")
    @PreAuthorize("hasAuthority('locker:customer:disable')")
    @PostMapping("/customers/{customerId}/status")
    public R<Void> changeCustomerStatus(@PathVariable Long customerId,
                                        @Valid @RequestBody CustomerStatusRequest request) {
        console.changeCustomerStatus(customerId, request.status(), request.reason());
        return R.ok();
    }

    /** 禁用与启用共用一个入口（状态只有两个值），但<b>事由必填</b>：无因的状态变更无法复盘。 */
    public record CustomerStatusRequest(@jakarta.validation.constraints.NotNull @Min(0) @Max(1) Integer status,
                                        @NotBlank @Size(max = 200) String reason) {
    }
}
