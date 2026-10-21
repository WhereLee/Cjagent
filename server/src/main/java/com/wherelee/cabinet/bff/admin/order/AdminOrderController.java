package com.wherelee.cabinet.bff.admin.order;

import com.wherelee.cabinet.storage.api.AdminOrderConsolePort;
import com.wherelee.cabinet.common.api.PageQuery;
import com.wherelee.cabinet.common.api.PageResult;
import com.wherelee.cabinet.common.api.R;
import com.wherelee.cabinet.domain.enums.OrderStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 后台订单查询（只读）。
 *
 * <p>补的是运营台缺的第一块：客服接到的第一句话通常是“我的订单号是 xxx，东西取不出来”。
 * 这里只做“查得到、看得清”，不提供任何写操作——退款、减免、强制结束仍走原有入口。
 *
 * <p>不加 {@code @OperationLog}：只读请求进审计会把表立刻灌满（与账号列表同一理由）。
 * 返回的是应用层视图（{@link AdminOrderConsolePort.OrderRow}），不回传实体（ArchUnit 会拦）。
 */
@Tag(name = "后台-订单", description = "按订单号/手机号/状态/时间查寄存单；只读，不改任何单")
@RestController
@RequestMapping("/api/admin/orders")
@Validated
public class AdminOrderController {

    private final AdminOrderConsolePort orders;

    public AdminOrderController(AdminOrderConsolePort orders) {
        this.orders = orders;
    }

    @Operation(summary = "订单分页列表",
            description = "默认按创建时间倒序；所有条件可选。手机号精确匹配，命中不到返回空页")
    @PreAuthorize("hasAuthority('order:list')")
    @GetMapping
    public R<PageResult<AdminOrderConsolePort.OrderRow>> list(
            @Valid PageQuery query,
            @RequestParam(required = false) @Size(max = 32) String orderNo,
            @RequestParam(required = false) @Size(max = 20) String phone,
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return R.ok(orders.list(orderNo, phone, status, from, to, query));
    }

    @Operation(summary = "订单详情",
            description = "状态、资金与账单流水、设备动作、格口证据、关联工单；跨租户查不到与不存在同一处理")
    @PreAuthorize("hasAuthority('order:list')")
    @GetMapping("/{orderNo}")
    public R<AdminOrderConsolePort.OrderDetail> detail(
            @PathVariable @NotBlank @Size(max = 32) String orderNo) {
        return R.ok(orders.detail(orderNo));
    }
}
