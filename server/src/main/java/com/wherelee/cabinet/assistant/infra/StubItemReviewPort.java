package com.wherelee.cabinet.assistant.infra;

import com.wherelee.cabinet.assistant.api.ItemReviewPort;
import com.wherelee.cabinet.locker.api.Presence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "cabinet.dispute.review.client", havingValue = "stub", matchIfMissing = true)
@Profile({"dev", "test", "integration"})
public class StubItemReviewPort implements ItemReviewPort {

    private static final Logger log = LoggerFactory.getLogger(StubItemReviewPort.class);

    private final Map<Long, Review> scripted = new ConcurrentHashMap<>();

    private volatile Review fallback = Review.unknown("桩未配置该格口的结论");

    @Override
    public Review review(Request request) {
        Review verdict = scripted.getOrDefault(request.compartmentId(), fallback);
        log.info("AI 复审 slot={} 结论={} 置信度={}",
                request.slotNo(), verdict.presence(), verdict.confidence());
        return verdict;
    }

    public void script(Long compartmentId, Presence presence, String note) {
        scripted.put(compartmentId, new Review(presence, null, note));
    }

    public void setFallback(Review review) {
        this.fallback = review == null ? Review.unknown("桩未配置") : review;
    }

    public void reset() {
        scripted.clear();
        fallback = Review.unknown("桩未配置该格口的结论");
    }
}
