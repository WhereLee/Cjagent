package com.wherelee.cabinet.interfaces.mini.notify;

import com.wherelee.cabinet.notify.api.NoticePort;
import com.wherelee.cabinet.common.api.R;
import com.wherelee.cabinet.common.annotation.RateLimit;
import com.wherelee.cabinet.platform.security.Caller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 客户的站内消息（19D）。
 *
 * <p>我们没有推送通道，所以"提醒"对客户而言只有这一种落点：他自己打开小程序时能看到
 * "你的格口门没关 / 有物品滞留"。<b>可见即提醒</b>，不假装有消息推送。
 */
@Tag(name = "客户-消息", description = "与己相关的异常提醒（站内消息）")
@RestController
@RequestMapping("/api/mini/notices")
public class MiniNoticeController {

    private final NoticePort notices;

    public MiniNoticeController(NoticePort notices) {
        this.notices = notices;
    }

    @Operation(summary = "我的消息", description = "未读在前；含已读的最近 50 条")
    @RateLimit(limit = 60, windowSeconds = 60)
    @PreAuthorize("hasRole('CUSTOMER')")
    @GetMapping
    public R<List<NoticePort.View>> mine(@AuthenticationPrincipal Caller current) {
        return R.ok(notices.forCustomer(current.subjectId()));
    }

    @Operation(summary = "标记已读", description = "只能标自己收到的消息")
    @PreAuthorize("hasRole('CUSTOMER')")
    @PostMapping("/{id}/read")
    public R<Void> read(@AuthenticationPrincipal Caller current, @PathVariable Long id) {
        notices.markRead(id, current.subjectId());
        return R.ok();
    }
}
