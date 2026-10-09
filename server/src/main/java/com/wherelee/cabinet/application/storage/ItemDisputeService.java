package com.wherelee.cabinet.application.storage;

import com.wherelee.cabinet.domain.entity.BizItemEvidence;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.DisputeState;
import com.wherelee.cabinet.domain.enums.EvidenceSource;
import com.wherelee.cabinet.domain.enums.Presence;
import com.wherelee.cabinet.infrastructure.mapper.BizItemEvidenceMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 柜内物品争议的推进（设计文档 §5 的 L1→L4）。
 *
 * <p>只负责"证据 + 阶梯 + 争议字段"，<b>不碰钱也不碰订单主态</b>：结算与状态迁移仍归
 * {@link StorageOrderService}。理由是这两件事的失败后果不同——判错格口能不能卖是安全问题，
 * 算错钱是资损问题，混在一个方法里就会为了少写几行而让两者共用同一个回滚边界。
 *
 * <p>三条不可让的规则写在这里而不是散在调用方：
 * <ul>
 *   <li><b>争议期间计费不停</b>（I11）：本类只写证据与争议字段，绝不动 {@code started_at}，
 *       也绝不因"用户否认"而暂停计时——一旦"否认=暂停"，只要说没有钱就不涨，等待成本全压在平台；</li>
 *   <li><b>争议起始时刻只记第一次</b>：判为误报时结算终点退回这里。每次拦下都刷新，
 *       反复点否认就会把终点不断往后推，"免除"反而变成加费；</li>
 *   <li><b>复审次数有上限</b>：AI 调用不是免费的，不设上限就是给用户一个"反复点否认刷模型"的按钮；
 *       次数用尽时连一次调用都不发，直接转人工。</li>
 * </ul>
 *
 * <p>{@link #markBlocked} 用 {@code REQUIRES_NEW}：调用方（结束判据）紧接着要抛业务异常回滚，
 * 如果争议记录在同一个事务里，它会被一起滚掉——那等于"拦下了但什么都没留下"，
 * 事后既无法免除费用也无法统计误报率。
 */
@Service
public class ItemDisputeService {

    private static final Logger log = LoggerFactory.getLogger(ItemDisputeService.class);

    /** 复审之后的三种去向，调用方按它决定"放行结束 / 继续拦 / 转人工"。 */
    public enum Outcome {
        /** 红外说有、AI 看图说没有 → 判为设备误报，允许结束（并把计费终点退到争议起始时刻） */
        FALSE_ALARM,
        /** AI 也说有 → 继续拦，提示取走或声明放弃 */
        STILL_PRESENT,
        /** AI 无法判断，或复审次数用尽 → 转真人客服（不得退回红外的结论） */
        NEED_HUMAN
    }

    /**
     * @param message 给用户的下一步说法。<b>必须带着下一步</b>：只说"不能结束"就是逼用户打客服
     * @param reviews 本单已发生的复审次数（含本次）
     */
    public record Decision(Outcome outcome, String message, int reviews) {
    }

    private final BizItemEvidenceMapper evidenceMapper;
    private final BizStorageOrderMapper orderMapper;
    private final ItemReviewPort reviewPort;
    private final MeterRegistry meterRegistry;

    @Value("${cabinet.dispute.max-ai-review:2}")
    private int maxReviews;

    public ItemDisputeService(BizItemEvidenceMapper evidenceMapper, BizStorageOrderMapper orderMapper,
                              ItemReviewPort reviewPort, MeterRegistry meterRegistry) {
        this.evidenceMapper = evidenceMapper;
        this.orderMapper = orderMapper;
        this.reviewPort = reviewPort;
        this.meterRegistry = meterRegistry;
    }

    /**
     * 结束被物检拦下时先记账：留下这次观测，并在<b>第一次</b>拦下时记下争议起始时刻。
     *
     * <p>REQUIRES_NEW 的理由见类注释：调用方马上要抛异常，同事务写就会被一起回滚。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markBlocked(BizStorageOrder order, Presence presence, String note) {
        record(order, EvidenceSource.DEVICE_PROBE, presence, note);
        orderMapper.markDisputeStart(order.getId(), DisputeState.ITEM_DISPUTED, LocalDateTime.now());
    }

    /**
     * 用户否认"柜内有我的东西" → 触发 AI 看图复审（L3）。
     *
     * <p>本方法<b>不抛异常也不改订单主态</b>：把决定权交回调用方（放行时它要结算，拦住时它要报错）。
     * 争议字段在这里落库，钱与状态都不在这里动。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Decision deny(BizStorageOrder order) {
        int used = order.getAiReviewCount() == null ? 0 : order.getAiReviewCount();
        if (used >= maxReviews) {
            orderMapper.markReview(order.getId(), DisputeState.HUMAN_REVIEW, used);
            log.warn("复审次数已用尽，转人工 orderNo={} used={}", order.getOrderNo(), used);
            return new Decision(Outcome.NEED_HUMAN,
                    "已转人工核实，请稍候或联系客服；核实期间订单仍正常计费", used);
        }

        int attempt = used + 1;
        // closedPhotoRef / baselinePhotoRef 目前为 null：拍照与空格基准照片的采集随真实设备与
        // 业务运维上线（第 14 刀）。没有基准时模型只能答 UNKNOWN，这是**有意的**：
        // 让它凭空判"有/无"，等于把模型幻觉变成扣用户钱的依据。
        ItemReviewPort.Review review = reviewPort.review(new ItemReviewPort.Request(
                order.getId(), order.getSlotId(), "slot:" + order.getSlotId(), null, null));
        Presence verdict = review.presence() == null ? Presence.UNKNOWN : review.presence();
        record(order, EvidenceSource.AI, verdict, review.note());
        DisputeState next = review.indeterminate() ? DisputeState.HUMAN_REVIEW : DisputeState.AI_REVIEWED;
        orderMapper.markReview(order.getId(), next, attempt);

        if (review.indeterminate()) {
            // 模型说"不知道"绝不退回红外的"有"：那样这一步复审就白做了
            count("indeterminate");
            return new Decision(Outcome.NEED_HUMAN,
                    "复核无法确定柜内情况，已转人工核实；期间订单仍正常计费", attempt);
        }
        if (verdict == Presence.ABSENT) {
            // 红外说有 + AI 说没有 = 一次设备误报。这个计数是"该换传感器还是调阈值"的唯一依据
            count("false-alarm");
            log.info("AI 复核判为设备误报，允许结束 orderNo={} reviews={}", order.getOrderNo(), attempt);
            return new Decision(Outcome.FALSE_ALARM, null, attempt);
        }
        count("still-present");
        return new Decision(Outcome.STILL_PRESENT,
                attempt >= maxReviews
                        ? "复核仍检测到柜内物品：请取出后结束，或声明放弃（放弃后本格口需现场清理）"
                        : "复核仍检测到柜内物品：请开门取出后结束，或声明放弃物品", attempt);
    }

    /**
     * 这单是否已经攒够"设备误报"的证据：同一单里既有"物检说有"又有"AI 说没有"。
     *
     * <p>为什么必须两条都在：只凭 AI 一句"没有"就免除费用，等于让一个模型单方面给平台减收；
     * 只凭物检"有"则永远免不掉用户的钱。<b>免除要的是两个独立来源互相打脸</b>。
     */
    public boolean hasFalseAlarmVerdict(BizStorageOrder order) {
        var timeline = evidenceMapper.timelineOf(order.getSlotId(), 50);
        boolean deviceSaidPresent = timeline.stream().anyMatch(e ->
                order.getId().equals(e.getOrderId()) && e.getSource() != EvidenceSource.AI
                        && e.getPresence() == Presence.PRESENT);
        boolean aiSaidAbsent = timeline.stream().anyMatch(e ->
                order.getId().equals(e.getOrderId()) && e.getSource() == EvidenceSource.AI
                        && e.getPresence() == Presence.ABSENT);
        return deviceSaidPresent && aiSaidAbsent;
    }

    public int maxReviews() {
        return maxReviews;
    }

    private void record(BizStorageOrder order, EvidenceSource source, Presence presence, String note) {
        BizItemEvidence evidence = new BizItemEvidence();
        evidence.setOrderId(order.getId());
        evidence.setCabinetId(order.getCabinetId());
        evidence.setCompartmentId(order.getSlotId());
        evidence.setSource(source);
        evidence.setPresence(presence);
        evidence.setNote(truncate(note == null ? source.name() + " → " + presence : note));
        evidenceMapper.insert(evidence);
    }

    private String truncate(String note) {
        return note.length() <= 250 ? note : note.substring(0, 250) + "…";
    }

    private void count(String verdict) {
        Counter.builder("cabinet.item.review")
                .tag("verdict", verdict)
                .description("柜内物品 AI 复审结论分布（false-alarm 即红外误报数）")
                .register(meterRegistry)
                .increment();
    }
}
