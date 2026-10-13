package com.wherelee.cabinet.interfaces.mini.storage;

import com.wherelee.cabinet.application.storage.StorageQueryService;
import com.wherelee.cabinet.common.api.R;
import com.wherelee.cabinet.common.annotation.RateLimit;
import com.wherelee.cabinet.infrastructure.security.VerifiedToken;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户端只读查询：哪儿有柜机、柜机上还有多少能用的格子、我的单、我的钱。
 *
 * <p>全部只要求登录、全部限流：这些接口不改任何东西，但它们是用户“要不要走过去”的依据，
 * 被人脚本刷起来打的是数据库；而按默认拒绝口径，它们也不对匿名开放。
 *
 * <p>所有视图都只给编号不给内部 ID：内部 ID 一旦泄露到客户端，就会有人拿它去猜别的接口入参。
 */
@Tag(name = "客户-查询", description = "点位/柜机/我的寄存单/我的钱包")
@RestController
@RequestMapping("/api/mini/storage")
public class MiniStorageQueryController {

    private final StorageQueryService queries;

    public MiniStorageQueryController(StorageQueryService queries) {
        this.queries = queries;
    }

    @Operation(summary = "点位列表", description = "带每个点位下的暂存柜台数")
    @RateLimit(limit = 60, windowSeconds = 60)
    @PreAuthorize("hasRole('CUSTOMER')")
    @GetMapping("/sites")
    public R<List<StorageQueryService.SiteView>> sites() {
        return R.ok(queries.sites());
    }

    @Operation(summary = "点位下的暂存柜", description = "状态与在线情况原样读出，不在查询层下判断")
    @RateLimit(limit = 60, windowSeconds = 60)
    @PreAuthorize("hasRole('CUSTOMER')")
    @GetMapping("/sites/{siteCode}/cabinets")
    public R<List<StorageQueryService.CabinetView>> cabinets(
            @PathVariable @NotBlank @Size(max = 32) String siteCode) {
        return R.ok(queries.cabinets(siteCode));
    }

    @Operation(summary = "我的寄存单", description = "最近若干张（含进行中那张）；默认 20 张，上限 50")
    @RateLimit(limit = 60, windowSeconds = 60)
    @PreAuthorize("hasRole('CUSTOMER')")
    @GetMapping("/orders")
    public R<List<StorageQueryService.OrderView>> myOrders(@AuthenticationPrincipal VerifiedToken current,
                                                           @RequestParam(defaultValue = "20") int limit) {
        return R.ok(queries.myOrders(current.subjectId(), limit));
    }

    @Operation(summary = "一张单的账单明细",
            description = "单价/免费窗口/封顶参数取自这张单的快照，不拿当前价重算；逐笔流水一并列出")
    @RateLimit(limit = 60, windowSeconds = 60)
    @PreAuthorize("hasRole('CUSTOMER')")
    @GetMapping("/orders/{orderNo}/bill")
    public R<StorageQueryService.BillView> bill(@AuthenticationPrincipal VerifiedToken current,
                                                @PathVariable @NotBlank @Size(max = 32) String orderNo) {
        return R.ok(queries.bill(current.subjectId(), orderNo));
    }

    @Operation(summary = "取件凭据",
            description = "第 25 刀：把码交给已登录的本人（只交活单，终态不回显）。"
                    + "凭码取件是柜机屏那条无登录态路径，双因子与错次锁定不变")
    @RateLimit(limit = 30, windowSeconds = 60)
    @PreAuthorize("hasRole('CUSTOMER')")
    @GetMapping("/orders/{orderNo}/voucher")
    public R<com.wherelee.cabinet.application.storage.dto.VoucherView> voucher(
            @AuthenticationPrincipal VerifiedToken current,
            @PathVariable @NotBlank @Size(max = 32) String orderNo) {
        return R.ok(queries.voucher(current.subjectId(), orderNo));
    }

    @Operation(summary = "我的钱包",
            description = "余额/冻结/押金栏 + 欠费合计与未支付笔数（欠费记在单上，这里读同一来源）")
    @RateLimit(limit = 60, windowSeconds = 60)
    @PreAuthorize("hasRole('CUSTOMER')")
    @GetMapping("/wallet")
    public R<StorageQueryService.WalletView> wallet(@AuthenticationPrincipal VerifiedToken current) {
        return R.ok(queries.wallet(current.subjectId()));
    }
}
