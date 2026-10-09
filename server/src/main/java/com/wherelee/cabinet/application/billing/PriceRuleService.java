package com.wherelee.cabinet.application.billing;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.entity.BizPriceRule;
import com.wherelee.cabinet.domain.entity.BizSite;
import com.wherelee.cabinet.domain.enums.SizeType;
import com.wherelee.cabinet.infrastructure.mapper.BizPriceRuleMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizSiteMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 计价策略的发布与按点位灰度（第 14D 刀）。
 *
 * <p>三条边界写在类上，因为它们决定了"改价"会不会变成事故：
 * <ol>
 *   <li><b>只影响新单</b>：策略在下单时抄进 {@code pricing_snapshot}，结算永远读快照。
 *       所以"发布"与"回滚"都不需要担心改到进行中的单——这条不是约定，是读路径上根本没有入口；</li>
 *   <li><b>同一范围只有一条已生效</b>：发布时把旧的标 SUPERSEDED，而<b>未来生效的那条不能被标掉</b>
 *       （条件写在 SQL 的 {@code effective_at <= now(3)} 上）。漏掉这一步不会当场报错，
 *       只会让某天零点切换时该范围一条策略都不剩，默默退回配置默认价；</li>
 *   <li><b>没有策略时退回 yml 默认值</b>：不是报错也不是免费。这让"发布策略"变成渐进式上线，
 *       但也意味着<b>没发布过的租户永远在吃默认价</b>，所以预览接口把当前来源一并返回，
 *       免得运营以为自己在管价格而其实改的是空气。</li>
 * </ol>
 */
@Service
public class PriceRuleService {

    private static final Logger log = LoggerFactory.getLogger(PriceRuleService.class);

    private final BizPriceRuleMapper ruleMapper;
    private final BizSiteMapper siteMapper;
    private final PricingPolicy pricing;

    public PriceRuleService(BizPriceRuleMapper ruleMapper, BizSiteMapper siteMapper, PricingPolicy pricing) {
        this.ruleMapper = ruleMapper;
        this.siteMapper = siteMapper;
        this.pricing = pricing;
    }

    /**
     * 发布入参（命令对象）。<b>放在应用层而不是 Controller 里</b>：它要转成实体，
     * 而接口层依赖 domain.entity 是ArchUnit 直接拦的反向依赖。
     *
     * <p>字段校验也落在服务里（{@code validate}）而不是注解上：校验规则跟“能不能算钱”强相关，
     * 两处各写一份迟早不一致。
     */
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

    /** 策略出参视图。<b>接口层不得直接拿实体</b>（ArchUnit 有这条规则）：
     * 实体带着审计列与逻辑删除位，一曝光到契约上就再也收不回去（前端会开始依赖它）。
     */
    public record View(Long id, Long siteId, Integer version, String status,
                       Long depositPoints, Integer freeMinutes, Integer dailyCapHours, Integer capDays,
                       Integer toleranceMinutes, Integer remoteCloseHours,
                       Long unitSmall, Long unitMedium, Long unitLarge,
                       LocalDateTime effectiveAt, Long publishedBy, String reason) {

        static View of(BizPriceRule rule) {
            if (rule == null) {
                return null;
            }
            return new View(rule.getId(), rule.getSiteId(), rule.getVersion(), rule.getStatus(),
                    rule.getDepositPoints(), rule.getFreeMinutes(), rule.getDailyCapHours(), rule.getCapDays(),
                    rule.getToleranceMinutes(), rule.getRemoteCloseHours(),
                    rule.getUnitSmall(), rule.getUnitMedium(), rule.getUnitLarge(),
                    rule.getEffectiveAt(), rule.getPublishedBy(), rule.getReason());
        }
    }

    /**
     * 当前生效策略（实体形式，给下单算价用）。可能为空（该租户从没发布过），
     * 调用方必须显式处理"退回默认价"这条分支，不许把 null 当 0 价——那是白送。
     */
    public BizPriceRule effective(Long siteId) {
        return siteId == null ? null : ruleMapper.selectEffective(siteId);
    }

    /** 给后台接口看的同一件事（视图形式，实体不外泄）。 */
    public View effectiveView(Long siteId) {
        return View.of(effective(siteId));
    }

    public List<View> list() {
        return ruleMapper.listAll().stream().map(View::of).toList();
    }

    /**
     * 发布前的预览：与下单/结算走<b>同一个 PricingPolicy</b>，绝不在这里重算一遍公式。
     *
     * <p>草稿也要先过 {@code validate}：预览看似只读，但算价会把 null 直接拆箱成 int——
     * 表单填一半就预览会把 500 系统异常报给用户（本条就是冷跑预览时发现的）。
     * 拒绝它不只为了状态码好看：带缺参的“价”根本不是一个可比较的结果。
     */
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
        // 草稿没有版本号（还没发布）；把 null 写成 "rule:null" 会让人以为存在这样一版
        result.put("source", fromDraft ? "draft" : "rule:v" + source.getVersion());
        result.put("depositPoints", source.getDepositPoints());
        result.put("samples", sample);
        return result;
    }

    /**
     * 发布一版策略。
     *
     * <p>版本号由服务端按范围算（{@code nextVersion}），不接受客户端传版本：
     * 让调用方指定版本就等于允许覆盖别人的那一版。唯一键 {@code (tenant, site, version)}
     * 是最后一道防线，专门挡"两个运营同时点发布"。
     */
    @Transactional
    public View publish(BizPriceRule draft, Long siteId, LocalDateTime effectiveAt,
                               String reason, Long adminId) {
        if (reason == null || reason.isBlank()) {
            throw new BizException(ResultCode.PARAM_INVALID, "发布计价策略必须写明事由（改价依据与灰度范围）");
        }
        if (siteId != null && siteMapper.selectById(siteId) == null) {
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

        // 立即生效才需要替代旧的；未来生效的留着，到点由 selectEffective 的时间条件自然切换
        if (!when.isAfter(LocalDateTime.now())) {
            int superseded = ruleMapper.supersedeCurrent(siteId, rule.getId());
            log.info("发布计价策略 siteId={} version={} 替代旧版 {} 条 发布人={} 事由={}",
                    siteId, rule.getVersion(), superseded, adminId, reason.trim());
        } else {
            log.info("预约发布计价策略 siteId={} version={} 生效时刻={}", siteId, rule.getVersion(), when);
        }
        return View.of(rule);
    }

    /**
     * 回滚 = <b>把旧版内容再发布一次</b>，而不是把旧行改回 LIVE。
     *
     * <p>差别不是洁癖：改回 LIVE 会让"谁在什么时候把价改成了什么"这条链断掉，
     * 而价格是要对账、要回答用户质疑的东西。新增一版能保留完整历史，也让"回滚"这件事本身可审计。
     */
    @Transactional
    public View rollback(Long ruleId, String reason, Long adminId) {
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
