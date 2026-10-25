package com.wherelee.cabinet.funds.api;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public interface PricingConsolePort {

    record Draft(Long siteId, LocalDateTime effectiveAt, String reason,
                 Long depositPoints, Integer freeMinutes, Integer dailyCapHours, Integer capDays,
                 Integer toleranceMinutes, Integer remoteCloseHours,
                 Long unitSmall, Long unitMedium, Long unitLarge) {
    }

    record PricingRuleView(Long id, Long siteId, Integer version, String status,
                Long depositPoints, Integer freeMinutes, Integer dailyCapHours, Integer capDays,
                Integer toleranceMinutes, Integer remoteCloseHours,
                Long unitSmall, Long unitMedium, Long unitLarge,
                LocalDateTime effectiveAt, Long publishedBy, String reason) {
    }

    PricingRuleView effective(Long siteId);

    List<PricingRuleView> list();

    Map<String, Object> preview(Long siteId, Draft draft);

    PricingRuleView publish(Draft draft, Long siteId, LocalDateTime effectiveAt, String reason, Long adminId);

    PricingRuleView rollback(Long ruleId, String reason, Long adminId);
}
