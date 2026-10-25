package com.wherelee.cabinet.funds.api;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 计价策略的后台入口（发布、灰度、回滚、预览）。
 *
 * <p>为什么放在 {@code api}：BFF 只允许依赖各服务的 api。这里的 {@link Draft} 是纯 DTO，
 * 不是 {@code BizPriceRule} 实体——接口层拿实体，审计列与逻辑删除位就会渗进对外契约，
 * 而且调用方的字节码会直接引用资金域的实体类型（ArchUnit 的跨服务规则会拦）。
 *
 * <p>预览与下单/结算走同一个算价策略：页面上看到的数字必须就是真实会扣的数字。
 */
public interface PricingConsolePort {

    /** 发布入参（命令对象）。字段与实体同名，便于契约稳定；转换在实现侧完成。 */
    record Draft(Long siteId, LocalDateTime effectiveAt, String reason,
                 Long depositPoints, Integer freeMinutes, Integer dailyCapHours, Integer capDays,
                 Integer toleranceMinutes, Integer remoteCloseHours,
                 Long unitSmall, Long unitMedium, Long unitLarge) {
    }

    /** 策略出参视图。 */
    record PricingRuleView(Long id, Long siteId, Integer version, String status,
                Long depositPoints, Integer freeMinutes, Integer dailyCapHours, Integer capDays,
                Integer toleranceMinutes, Integer remoteCloseHours,
                Long unitSmall, Long unitMedium, Long unitLarge,
                LocalDateTime effectiveAt, Long publishedBy, String reason) {
    }

    /** 当前生效策略；没有时返回 null（调用方必须显式处理“退回默认价”）。 */
    PricingRuleView effective(Long siteId);

    List<PricingRuleView> list();

    /** 发布前预览：与结算同一算法；{@code draft} 为空时按当前生效策略算。 */
    Map<String, Object> preview(Long siteId, Draft draft);

    PricingRuleView publish(Draft draft, Long siteId, LocalDateTime effectiveAt, String reason, Long adminId);

    /** 回滚 = 把旧版内容再发布一次（保留历史，不把旧行改回生效）。 */
    PricingRuleView rollback(Long ruleId, String reason, Long adminId);
}
