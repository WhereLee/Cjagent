package com.wherelee.cabinet.funds.app;

import com.wherelee.cabinet.funds.api.PricingConsolePort;
import com.wherelee.cabinet.funds.domain.BizPriceRule;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * BFF 侧计价入口的实现：把 api 的 DTO 转成资金域实体，再交给 {@link PriceRuleService}。
 *
 * <p>转换放在这一层而不是让 {@link BizPriceRule} 出现在 api 里：对外契约只认字段，
 * 实体上的审计列、逻辑删除位与版本号不能顺着“发布一版策略”漏到前端。
 */
@Service
public class PricingConsoleAdapter implements PricingConsolePort {

    private final PriceRuleService rules;

    public PricingConsoleAdapter(PriceRuleService rules) {
        this.rules = rules;
    }

    @Override
    public View effective(Long siteId) {
        return toView(rules.effectiveView(siteId));
    }

    @Override
    public List<View> list() {
        return rules.list().stream().map(PricingConsoleAdapter::toView).toList();
    }

    @Override
    public Map<String, Object> preview(Long siteId, Draft draft) {
        return rules.preview(siteId, draft == null ? null : toRule(draft));
    }

    @Override
    public View publish(Draft draft, Long siteId, LocalDateTime effectiveAt, String reason, Long adminId) {
        return toView(rules.publish(toRule(draft), siteId, effectiveAt, reason, adminId));
    }

    @Override
    public View rollback(Long ruleId, String reason, Long adminId) {
        return toView(rules.rollback(ruleId, reason, adminId));
    }

    private BizPriceRule toRule(Draft draft) {
        BizPriceRule rule = new BizPriceRule();
        rule.setDepositPoints(draft.depositPoints());
        rule.setFreeMinutes(draft.freeMinutes());
        rule.setDailyCapHours(draft.dailyCapHours());
        rule.setCapDays(draft.capDays());
        rule.setToleranceMinutes(draft.toleranceMinutes());
        rule.setRemoteCloseHours(draft.remoteCloseHours());
        rule.setUnitSmall(draft.unitSmall());
        rule.setUnitMedium(draft.unitMedium());
        rule.setUnitLarge(draft.unitLarge());
        return rule;
    }

    private static View toView(PriceRuleService.View view) {
        if (view == null) {
            return null;
        }
        return new View(view.id(), view.siteId(), view.version(), view.status(),
                view.depositPoints(), view.freeMinutes(), view.dailyCapHours(), view.capDays(),
                view.toleranceMinutes(), view.remoteCloseHours(),
                view.unitSmall(), view.unitMedium(), view.unitLarge(),
                view.effectiveAt(), view.publishedBy(), view.reason());
    }
}
