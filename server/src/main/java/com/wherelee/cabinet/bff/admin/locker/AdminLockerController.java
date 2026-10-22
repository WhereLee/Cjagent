package com.wherelee.cabinet.bff.admin.locker;

import com.wherelee.cabinet.locker.api.LockerConsolePort;
import com.wherelee.cabinet.locker.api.SlotHoldPort;
import com.wherelee.cabinet.common.annotation.Idempotent;
import com.wherelee.cabinet.common.annotation.OperationLog;
import com.wherelee.cabinet.common.api.PageQuery;
import com.wherelee.cabinet.common.api.PageResult;
import com.wherelee.cabinet.common.api.R;
import com.wherelee.cabinet.platform.security.Caller;
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

    private final LockerConsolePort console;
    /** 空闲集合校准是预占策略的运行时结构，归 locker 管；不预占的策略下它只是空转。 */
    private final SlotHoldPort slotHold;
    /** 客户禁用/启用是身份的事：控制器只转发，不在柜机服务里改客户表。 */
    private final com.wherelee.cabinet.identity.api.CustomerAdminPort customers;

    public AdminLockerController(LockerConsolePort console, SlotHoldPort slotHold,
                                 com.wherelee.cabinet.identity.api.CustomerAdminPort customers) {
        this.console = console;
        this.slotHold = slotHold;
        this.customers = customers;
    }

    // ------------------------------------------------------------------ 只读

    @Operation(summary = "异常格口台账", description = "默认按判定时刻升序（卡最久的在前）；可按原因码与点位过滤")
    @PreAuthorize("hasAuthority('locker:ledger:list')")
    @GetMapping("/compartments/anomalies")
    public R<PageResult<LockerConsolePort.LedgerView>> anomalies(@Valid PageQuery query,
                                                                     @RequestParam(required = false)
                                                                     @Size(max = 24) String anomaly,
                                                                     @RequestParam(required = false) Long siteId) {
        return R.ok(console.anomalies(query, anomaly, siteId));
    }

    @Operation(summary = "异常积压概览", description = "按原因码给出条数与最长停留分钟，排班看这个数")
    @PreAuthorize("hasAuthority('locker:ledger:list')")
    @GetMapping("/compartments/anomalies/summary")
    public R<List<LockerConsolePort.SummaryView>> anomalySummary(
            @RequestParam(required = false) Long siteId) {
        return R.ok(console.anomalySummary(siteId));
    }

    @Operation(summary = "格口详情", description = "现状 + 证据时间线 + 当前占用的单")
    @PreAuthorize("hasAuthority('locker:ledger:list')")
    @GetMapping("/compartments/{compartmentId}")
    public R<LockerConsolePort.Detail> detail(@PathVariable Long compartmentId) {
        return R.ok(console.compartmentDetail(compartmentId));
    }

    @Operation(summary = "未退押金清单", description = "只列订单已终态而押金凭证仍未退还的（在线单挂着押金是正常态）")
    @PreAuthorize("hasAuthority('locker:deposit:unrefunded-list')")
    @GetMapping("/deposits/unrefunded")
    public R<PageResult<LockerConsolePort.DepositView>> unrefunded(@Valid PageQuery query) {
        return R.ok(console.unrefundedDeposits(query));
    }

    @Operation(summary = "欠费客户清单", description = "按客户汇总未缴点数，降序")
    @PreAuthorize("hasAuthority('locker:arrears:list')")
    @GetMapping("/arrears")
    public R<PageResult<LockerConsolePort.ArrearsView>> arrears(@Valid PageQuery query) {
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
    public R<Void> forceOpen(@AuthenticationPrincipal Caller current,
                             @PathVariable Long compartmentId,
                             @Valid @RequestBody ForceOpenRequest request) {
        console.forceOpen(compartmentId, request.confirmed(), request.reason(), current.subjectId());
        return R.ok();
    }

    @Operation(summary = "重建空闲格口集合", description = "以 DB 真相同步 Redis 准入集合；先 DEL 再 SADD，期间会少卖不会超卖")
    @OperationLog(module = "locker-console", operation = "重建空闲集合")
    @Idempotent(message = "重建正在进行中，请稍候")
    @PreAuthorize("hasAuthority('locker:freeset:rebuild')")
    @PostMapping("/cabinets/{cabinetId}/free-set/rebuild")
    public R<Void> rebuildFreeSet(@PathVariable Long cabinetId) {
        slotHold.syncAllFreeSets(cabinetId);
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
        customers.changeCustomerStatus(customerId, request.status(), request.reason());
        return R.ok();
    }

    /** 禁用与启用共用一个入口（状态只有两个值），但<b>事由必填</b>：无因的状态变更无法复盘。 */
    public record CustomerStatusRequest(@jakarta.validation.constraints.NotNull @Min(0) @Max(1) Integer status,
                                        @NotBlank @Size(max = 200) String reason) {
    }
}
