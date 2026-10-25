package com.wherelee.cabinet.funds.app;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.funds.domain.BizPriceRule;
import com.wherelee.cabinet.locker.api.SlotQueryPort;
import com.wherelee.cabinet.locker.api.SizeType;
import com.wherelee.cabinet.funds.infra.BizPriceRuleMapper;
import com.wherelee.cabinet.locker.api.SizeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PriceRuleService {

    private static final Logger log = LoggerFactory.getLogger(PriceRuleService.class);

    private final BizPriceRuleMapper ruleMapper;
    private final SlotQueryPort slotQueries;
    private final PricingPolicy pricing;

    public PriceRuleService(BizPriceRuleMapper ruleMapper, SlotQueryPort slotQueries, PricingPolicy pricing) {
        this.ruleMapper = ruleMapper;
        this.slotQueries = slotQueries;
        this.pricing = pricing;
    }

    public record Draft(Long siteId, LocalDateTime effectiveAt, String reason,
                        Long depositPoints, Integer freeMinutes, Integer dailyCapHours, Integer capDays,
                        Integer toleranceMinutes, Integer remoteCloseHours,
                        Long unitSmall, Long unitMedium, Long unitLarge) {

        public BizPriceRule toRule() {
            BizPriceRule rule = new BizPriceRule();
            rule.setDepositPoints(depositPoints);
            rule.setFreeMinutes(freeMinutes);
            rule.setDailyCapHours(dailyCapHours);
            rule.setCapDays(capDays);
            rule.setToleranceMinutes(toleranceMinutes);
            rule.setRemoteCloseHours(remoteCloseHours);
            rule.setUnitSmall(unitSmall);
            rule.setUnitMedium(unitMedium);
            rule.setUnitLarge(unitLarge);
            return rule;
        }
    }

    public record PricingRuleView(Long id, Long siteId, Integer version, String status,
                       Long depositPoints, Integer freeMinutes, Integer dailyCapHours, Integer capDays,
                       Integer toleranceMinutes, Integer remoteCloseHours,
                       Long unitSmall, Long unitMedium, Long unitLarge,
                       LocalDateTime effectiveAt, Long publishedBy, String reason) {

        static PricingRuleView of(BizPriceRule rule) {
            if (rule == null) {
                return null;
            }
            return new PricingRuleView(rule.getId(), rule.getSiteId(), rule.getVersion(), rule.getStatus(),
                    rule.getDepositPoints(), rule.getFreeMinutes(), rule.getDailyCapHours(), rule.getCapDays(),
                    rule.getToleranceMinutes(), rule.getRemoteCloseHours(),
                    rule.getUnitSmall(), rule.getUnitMedium(), rule.getUnitLarge(),
                    rule.getEffectiveAt(), rule.getPublishedBy(), rule.getReason());
        }
    }

    public BizPriceRule effective(Long siteId) {
        return ruleMapper.selectEffective(siteId);
    }

    public PricingRuleView effectiveView(Long siteId) {
        return PricingRuleView.of(effective(siteId));
    }

    public List<PricingRuleView> list() {
        return ruleMapper.listAll().stream().map(PricingRuleView::of).toList();
    }

    public Map<String, Object> preview(Long siteId, BizPriceRule draft) {
        boolean fromDraft = draft != null;
        if (fromDraft) {
            validate(draft);
        }
        BizPriceRule source = fromDraft ? draft : effective(siteId);
        if (source == null) {
            throw new BizException(ResultCode.BIZ_ERROR, "该范围还没有已生效的策略，请带上草稿参数一起预览");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> sample = new LinkedHashMap<>();
        for (SizeType size : SizeType.values()) {
            for (long minutes : new long[]{30, 90, 60 * 26, 60 * 80}) {
                var quote = fromDraft
                        ? pricing.quoteByRule(size, minutes, source)
                        : pricing.quote(size, minutes);
                sample.put(size.name() + "@" + minutes + "min", quote.consumePoints());
            }
        }
        result.put("source", fromDraft ? "draft" : "rule:v" + source.getVersion());
        result.put("depositPoints", source.getDepositPoints());
        result.put("samples", sample);
        return result;
    }

    @Transactional
    public PricingRuleView publish(BizPriceRule draft, Long siteId, LocalDateTime effectiveAt,
                               String reason, Long adminId) {
        if (reason == null || reason.isBlank()) {
            throw new BizException(ResultCode.PARAM_INVALID, "发布计价策略必须写明事由（改价依据与灰度范围）");
        }
        if (siteId != null && slotQueries.site(siteId).isEmpty()) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "点位不存在，无法为其发布策略");
        }
        validate(draft);
        LocalDateTime when = effectiveAt == null ? LocalDateTime.now() : effectiveAt;

        BizPriceRule rule = copyValues(draft);
        rule.setId(IdWorker.getId());
        rule.setSiteId(siteId);
        rule.setVersion(ruleMapper.nextVersion(siteId));
        rule.setStatus(BizPriceRule.LIVE);
        rule.setEffectiveAt(when);
        rule.setPublishedBy(adminId);
        rule.setReason(reason.trim());
        ruleMapper.insert(rule);

        if (!when.isAfter(LocalDateTime.now())) {
            int superseded = ruleMapper.supersedeCurrent(siteId, rule.getId());
            log.info("发布计价策略 siteId={} version={} 替代旧版 {} 条 发布人={} 事由={}",
                    siteId, rule.getVersion(), superseded, adminId, reason.trim());
        } else {
            log.info("预约发布计价策略 siteId={} version={} 生效时刻={}", siteId, rule.getVersion(), when);
        }
        return PricingRuleView.of(rule);
    }

    @Transactional
    public PricingRuleView rollback(Long ruleId, String reason, Long adminId) {
        BizPriceRule old = ruleMapper.selectById(ruleId);
        if (old == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "要回滚的策略版本不存在");
        }
        return publish(old, old.getSiteId(), LocalDateTime.now(),
                "回滚到 v" + old.getVersion() + "：" + reason, adminId);
    }

    private void validate(BizPriceRule draft) {
        requirePositive(draft.getDepositPoints(), "押金点数");
        requireNonNegative(draft.getFreeMinutes(), "免费窗口");
        requireNonNegative(draft.getDailyCapHours(), "单日封顶小时数");
        requireNonNegative(draft.getCapDays(), "封顶天数");
        requireNonNegative(draft.getToleranceMinutes(), "容错分钟数");
        requireNonNegative(draft.getRemoteCloseHours(), "远程结束加收小时数");
        requirePositive(draft.getUnitSmall(), "小格口单价");
        requirePositive(draft.getUnitMedium(), "中格口单价");
        requirePositive(draft.getUnitLarge(), "大格口单价");
    }

    private void requirePositive(Long value, String name) {
        if (value == null || value <= 0) {
            throw new BizException(ResultCode.PARAM_INVALID, name + "必须大于 0（给 0 等于白送，比报错危险得多）");
        }
    }

    private void requireNonNegative(Integer value, String name) {
        if (value == null || value < 0) {
            throw new BizException(ResultCode.PARAM_INVALID, name + "不能为负；0 表示不限制");
        }
    }

    private BizPriceRule copyValues(BizPriceRule source) {
        BizPriceRule target = new BizPriceRule();
        target.setDepositPoints(source.getDepositPoints());
        target.setFreeMinutes(source.getFreeMinutes());
        target.setDailyCapHours(source.getDailyCapHours());
        target.setCapDays(source.getCapDays());
        target.setToleranceMinutes(source.getToleranceMinutes());
        target.setRemoteCloseHours(source.getRemoteCloseHours());
        target.setUnitSmall(source.getUnitSmall());
        target.setUnitMedium(source.getUnitMedium());
        target.setUnitLarge(source.getUnitLarge());
        return target;
    }
}
