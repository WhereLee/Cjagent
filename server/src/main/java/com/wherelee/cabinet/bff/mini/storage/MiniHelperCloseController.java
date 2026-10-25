package com.wherelee.cabinet.bff.mini.storage;

import com.wherelee.cabinet.locker.api.CabinetAvailabilityPort;
import com.wherelee.cabinet.common.api.R;
import com.wherelee.cabinet.common.annotation.RateLimit;
import com.wherelee.cabinet.platform.security.Caller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "客户-代关", description = "上一位的格口门没关：帮忙关上并确认结果")
@RestController
@RequestMapping("/api/mini/storage/cabinets")
public class MiniHelperCloseController {

    private final CabinetAvailabilityPort cabinets;

    public MiniHelperCloseController(CabinetAvailabilityPort cabinets) {
        this.cabinets = cabinets;
    }

    @Operation(summary = "这台暂存柜上只差关门的格口", description = "只列「门未关」一类，其余异常不该让外人处理")
    @RateLimit(limit = 60, windowSeconds = 60)
    @PreAuthorize("hasRole('CUSTOMER')")
    @GetMapping("/{cabinetNo}/closable-slots")
    public R<List<CabinetAvailabilityPort.ClosableView>> closable(
            @PathVariable @NotBlank @Size(max = 32) String cabinetNo) {
        return R.ok(cabinets.closableSlots(cabinetNo));
    }

    @Operation(summary = "我帮他把门关上了", description = "返回关上后的检测结果：无物则异常解除，有物则转人工")
    @RateLimit(limit = 10, windowSeconds = 60, message = "操作过于频繁，请稍后再试")
    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping("/{cabinetNo}/slots/{slotNo}/closed-by-helper")
    public R<String> confirmClosed(@AuthenticationPrincipal Caller current,
                                   @PathVariable @NotBlank @Size(max = 32) String cabinetNo,
                                   @PathVariable @NotBlank @Size(max = 16) String slotNo) {
        return R.ok(cabinets.confirmClosedByAnother(cabinetNo, slotNo, current.subjectId()));
    }
}
