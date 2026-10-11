package com.wherelee.cabinet.interfaces.mini.storage;

import com.wherelee.cabinet.application.storage.CompartmentStateService;
import com.wherelee.cabinet.common.api.R;
import com.wherelee.cabinet.common.annotation.RateLimit;
import com.wherelee.cabinet.infrastructure.security.VerifiedToken;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 顺手代关（19D 第 4 条的端点）。
 *
 * <p>为什么把它做成一个明确的动作而不是"用户自己默默关了就完了"：系统需要知道**是谁关的**，
 * 也要让用户立刻看到"关上之后柜内还有东西"这种结果，否则他关完就走，下一位开开的仍是有件的格子。
 *
 * <p>这里只受理"当前异常是门未关"的格口（判据在业务层），遗留物与硬件故障都不在这条路上。
 */
@Tag(name = "客户-代关", description = "上一位的格口门没关：帮忙关上并确认结果")
@RestController
@RequestMapping("/api/mini/storage/compartments")
public class MiniHelperCloseController {

    private final CompartmentStateService states;

    public MiniHelperCloseController(CompartmentStateService states) {
        this.states = states;
    }

    @Operation(summary = "这台暂存柜上只差关门的格口", description = "只列「门未关」一类，其余异常不该让外人处理")
    @RateLimit(limit = 60, windowSeconds = 60)
    @PreAuthorize("hasRole('CUSTOMER')")
    @GetMapping("/closable")
    public R<List<CompartmentStateService.ClosableSlot>> closable(@RequestParam Long cabinetId) {
        return R.ok(states.closableSlots(cabinetId));
    }

    @Operation(summary = "我帮他把门关上了", description = "返回关上后的检测结果：无物则异常解除，有物则转人工")
    @RateLimit(limit = 10, windowSeconds = 60, message = "操作过于频繁，请稍后再试")
    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping("/{slotId}/closed-by-helper")
    public R<String> confirmClosed(@AuthenticationPrincipal VerifiedToken current,
                                   @PathVariable Long slotId) {
        return R.ok(states.confirmClosedByAnother(slotId, current.subjectId()));
    }
}
