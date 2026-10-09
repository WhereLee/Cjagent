package com.wherelee.cabinet.infrastructure.ai;

import com.wherelee.cabinet.application.storage.ItemReviewPort;
import com.wherelee.cabinet.domain.enums.Presence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 复审的桩：按格口指定结论，默认一律"无法判断"。
 *
 * <p>它和柜机模拟器一样是<b>可控故障源</b>，不是模型还原：本项目的目的从来不是跑通一次识图，
 * 而是让业务侧在"AI 说有 / 说没有 / 说不知道 / 调用失败"四种答案下都走对该走的路。
 *
 * 默认值选 {@link Presence#UNKNOWN} 而不是 ABSENT，是有意为之：
 * 一个没配答案的用例如果默认放行，“AI 不可用就当没事”这条错路就永远测不出来。
 * 真实识图模型由 {@code cabinet.dispute.review.client=mimo} 切到 {@link MimoItemReviewPort}，
 * 业务代码不动，key 不进仓库。两个实现同一个开关互斥，<b>不靠 Profile 区分</b>：
 * 集成测试要跑在 dev profile 上，靠 profile 切会把“测试必须用桩”这个约束写成“测试碰巧用了桩”。
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "cabinet.dispute.review.client", havingValue = "stub", matchIfMissing = true)
@Profile({"dev", "test", "integration"})
public class StubItemReviewPort implements ItemReviewPort {

    private static final Logger log = LoggerFactory.getLogger(StubItemReviewPort.class);

    /** compartmentId → 指定结论；没配到就按"无法判断"。 */
    private final Map<Long, Review> scripted = new ConcurrentHashMap<>();

    private volatile Review fallback = Review.unknown("桩未配置该格口的结论");

    @Override
    public Review review(Request request) {
        Review verdict = scripted.getOrDefault(request.compartmentId(), fallback);
        log.info("AI 复审 slot={} 结论={} 置信度={}",
                request.slotNo(), verdict.presence(), verdict.confidence());
        return verdict;
    }

    /** 测试：指定某个格口的复审结论。 */
    public void script(Long compartmentId, Presence presence, String note) {
        scripted.put(compartmentId, new Review(presence, null, note));
    }

    /** 测试：让所有未显式配置的格口都返回这个结论（例如"模型超时"）。 */
    public void setFallback(Review review) {
        this.fallback = review == null ? Review.unknown("桩未配置") : review;
    }

    /** 测试之间必须显式隔离，否则上一个用例指定的结论会串到下一个。 */
    public void reset() {
        scripted.clear();
        fallback = Review.unknown("桩未配置该格口的结论");
    }
}
