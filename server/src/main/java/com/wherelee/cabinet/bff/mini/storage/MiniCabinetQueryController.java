package com.wherelee.cabinet.bff.mini.storage;

import com.wherelee.cabinet.locker.api.CabinetAvailabilityPort;
import com.wherelee.cabinet.common.api.R;
import com.wherelee.cabinet.common.annotation.RateLimit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "客户-柜机查询", description = "各尺寸剩余正常格口数")
@RestController
@RequestMapping("/api/mini/storage/cabinets")
public class MiniCabinetQueryController {

    private final CabinetAvailabilityPort availability;

    public MiniCabinetQueryController(CabinetAvailabilityPort availability) {
        this.availability = availability;
    }

    @Operation(summary = "这台暂存柜各尺寸剩余可用格口",
            description = "只统计正常可分配格口：异常（门未关/遗留物/待确认/故障）不计入，全异常即为 0")
    @RateLimit(limit = 60, windowSeconds = 60)
    @PreAuthorize("hasRole('CUSTOMER')")
    @GetMapping("/{cabinetNo}/availability")
    public R<CabinetAvailabilityPort.Availability> availability(
            @PathVariable @NotBlank @Size(max = 32) String cabinetNo) {
        return R.ok(availability.of(cabinetNo));
    }
}
