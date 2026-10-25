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
