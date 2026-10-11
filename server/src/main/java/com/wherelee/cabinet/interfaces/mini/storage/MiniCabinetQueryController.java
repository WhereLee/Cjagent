package com.wherelee.cabinet.interfaces.mini.storage;

import com.wherelee.cabinet.application.storage.CabinetAvailabilityService;
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

/**
 * 用户端只读查询：一台暂存柜还剩多少能用的格口（第 15 刀的第一层）。
 *
 * <p>它回答的是用户在柜机前真正要问的那一句：“我还用不用得上了”。数字只统计
 * <b>正常可分配</b>的格口：门没关的、有别人东西的、标了故障的都不算，所以三档都是 0 的柜机
 * 就不该再吸引人走过去（用户 2026-10-12 定的口径）。
 *
 * <p>这一层故意要登录：柜机屏那条路径以后要么带租户级令牌、要么走公开接口，
 * 现在先按默认拒绝口径不开匿名口子。
 */
@Tag(name = "客户-柜机查询", description = "各尺寸剩余正常格口数")
@RestController
@RequestMapping("/api/mini/storage/cabinets")
public class MiniCabinetQueryController {

    private final CabinetAvailabilityService availability;

    public MiniCabinetQueryController(CabinetAvailabilityService availability) {
        this.availability = availability;
    }

    @Operation(summary = "这台暂存柜各尺寸剩余可用格口",
            description = "只统计正常可分配格口：异常（门未关/遗留物/待确认/故障）不计入，全异常即为 0")
    @RateLimit(limit = 60, windowSeconds = 60)
    @PreAuthorize("hasRole('CUSTOMER')")
    @GetMapping("/{cabinetNo}/availability")
    public R<CabinetAvailabilityService.Availability> availability(
            @PathVariable @NotBlank @Size(max = 32) String cabinetNo) {
        return R.ok(availability.of(cabinetNo));
    }
}
