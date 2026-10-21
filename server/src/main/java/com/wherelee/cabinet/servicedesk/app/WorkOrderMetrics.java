package com.wherelee.cabinet.servicedesk.app;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 智能工单的可观测指标（批次 7）。
 *
 * <p>两类指标分开算，因为它们的真相源不同：
 * <ul>
 *   <li><b>AI 对话</b>：进程内计数器。自助解决率 = 未转报且 AI 正常应答 / 全部对话；
 *       兜底话术（AI 不可用/未开启）不算“AI 解决了”，否则指标会把故障说成成绩。</li>
 *   <li><b>工单结果</b>：按窗口从库里聚合。首次解决率、SLA 达成率、错误转报率、P95 处理时长
 *       都是历史结果，不能靠进程内计数器在重启后归零。</li>
 * </ul>
 *
 * <p>窗口默认 24 小时：这是“最近的运行状态”，不是财务口径。指标只读，不参与任何业务判定。
 */
@Service
public class WorkOrderMetrics implements com.wherelee.cabinet.servicedesk.api.WorkOrderMetricsPort {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderMetrics.class);

    private final JdbcTemplate jdbc;
    private final Counter chatAnswered;
    private final Counter chatEscalated;
    private final Counter chatFallback;
    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.empty());

    /** 统计窗口：只看最近这段时间内关闭的工单。 */
    @Value("${cabinet.work-order.metrics.window-hours:24}")
    private long windowHours;

    public WorkOrderMetrics(@Qualifier("mysqlDataSource") DataSource mysqlDataSource, MeterRegistry registry) {
        // 容器里还有 PG 的 JdbcTemplate（agent/向量库），工单指标必须显式绑业务主库
        this.jdbc = new JdbcTemplate(mysqlDataSource);
        this.chatAnswered = Counter.builder("cabinet.ai.chat")
                .tag("outcome", "answered").description("AI 正常应答且未转报").register(registry);
        this.chatEscalated = Counter.builder("cabinet.ai.chat")
                .tag("outcome", "escalated").description("AI 转报工单").register(registry);
        this.chatFallback = Counter.builder("cabinet.ai.chat")
                .tag("outcome", "fallback").description("AI 不可用/未开启的兜底").register(registry);

        Gauge.builder("cabinet.ai.self_service.ratio", this, WorkOrderMetrics::selfServiceRatio)
                .description("AI 自助解决率：未转报且正常应答 / 全部对话").register(registry);
        Gauge.builder("cabinet.ai.escalation.ratio", this, WorkOrderMetrics::escalationRatio)
                .description("AI 转报率：转报 / 全部对话").register(registry);

        Gauge.builder("cabinet.work_order.queue.backlog", this, m -> m.snapshot().backlogOnline())
                .tag("queue", "ONLINE").description("线上队列未关闭工单数").register(registry);
        Gauge.builder("cabinet.work_order.queue.backlog", this, m -> m.snapshot().backlogField())
                .tag("queue", "FIELD").description("线下队列未关闭工单数").register(registry);
        Gauge.builder("cabinet.work_order.queue.backlog.total", this,
                        m -> m.snapshot().backlogOnline() + m.snapshot().backlogField())
                .description("两条队列未关闭工单合计").register(registry);
        Gauge.builder("cabinet.work_order.first_fix.ratio", this, m -> m.snapshot().firstFixRate())
                .description("首次解决率：窗口内关闭且未被用户重开的比例").register(registry);
        Gauge.builder("cabinet.work_order.sla.attainment.ratio", this, m -> m.snapshot().slaRate())
                .description("SLA 达成率：窗口内关闭且未超过解决时限的比例").register(registry);
        Gauge.builder("cabinet.work_order.misreport.ratio", this, m -> m.snapshot().misreportRate())
                .description("错误转报率：AI 转报后被合并/作废的比例").register(registry);
        Gauge.builder("cabinet.work_order.handle.p95.seconds", this, m -> m.snapshot().p95Seconds())
                .description("窗口内关闭工单的处理时长 P95（秒）").register(registry);
    }

    @PostConstruct
    void init() {
        refresh();
    }

    /** 每次客服对话记一笔；兜底不是 AI 的功劳，单独记。 */
    @Override
    public void chat(boolean escalated, boolean fallback) {
        if (fallback) {
            chatFallback.increment();
        } else if (escalated) {
            chatEscalated.increment();
        } else {
            chatAnswered.increment();
        }
    }

    /** 窗口内工单结果的聚合。调度刷新而不是每次 scrape 查库：看板拉取频率不该打到工单表。 */
    @Scheduled(fixedDelayString = "${cabinet.work-order.metrics.refresh-ms:60000}")
    public void refresh() {
        try {
            LocalDateTime since = LocalDateTime.now().minusHours(Math.max(1, windowHours));
            Map<String, Long> backlog = new HashMap<>();
            jdbc.query("""
                    select queue_side, count(*) from biz_work_order
                     where deleted = 0 and status in ('PENDING', 'CLAIMED')
                     group by queue_side
                    """, rs -> {
                backlog.put(rs.getString(1), rs.getLong(2));
            });

            Map<String, Object> closed = jdbc.queryForMap("""
                    select count(*) as closed,
                           coalesce(sum(case when coalesce(reopened_count, 0) = 0 then 1 else 0 end), 0) as first_fix,
                           coalesce(sum(case when resolve_due_at is not null
                                              and closed_at <= resolve_due_at then 1 else 0 end), 0) as sla_ok,
                           coalesce(sum(case when resolve_due_at is not null then 1 else 0 end), 0) as sla_total,
                           coalesce(sum(case when source = 'AI' then 1 else 0 end), 0) as ai_closed,
                           coalesce(sum(case when source = 'AI'
                                              and close_reason in ('MERGED', 'CANCELLED') then 1 else 0 end), 0) as ai_bad
                      from biz_work_order
                     where deleted = 0 and status in ('RESOLVED', 'AUTO_CLOSED') and closed_at >= ?
                    """, since);

            long closedCount = number(closed.get("closed"));
            long firstFix = number(closed.get("first_fix"));
            long slaOk = number(closed.get("sla_ok"));
            long slaTotal = number(closed.get("sla_total"));
            long aiClosed = number(closed.get("ai_closed"));
            long aiBad = number(closed.get("ai_bad"));

            long p95Seconds = 0;
            if (closedCount > 0) {
                int offset = (int) Math.max(0, Math.ceil(closedCount * 0.95) - 1);
                Long seconds = jdbc.queryForObject("""
                        select timestampdiff(second, create_time, closed_at)
                          from biz_work_order
                         where deleted = 0 and status in ('RESOLVED', 'AUTO_CLOSED')
                           and closed_at >= ? and closed_at >= create_time
                         order by timestampdiff(second, create_time, closed_at)
                         limit 1 offset ?
                        """, Long.class, since, offset);
                p95Seconds = seconds == null ? 0 : Math.max(0, seconds);
            }

            snapshot.set(new Snapshot(
                    backlog.getOrDefault("ONLINE", 0L),
                    backlog.getOrDefault("FIELD", 0L),
                    ratio(firstFix, closedCount),
                    ratio(slaOk, slaTotal),
                    ratio(aiBad, aiClosed),
                    p95Seconds));
        } catch (RuntimeException e) {
            // 指标失败不能反过来影响业务线程；保留上一轮快照，日志里留一次原因
            log.warn("工单指标刷新失败，保留上一轮快照", e);
        }
    }

    public Snapshot snapshot() {
        return snapshot.get();
    }

    private double selfServiceRatio() {
        double total = chatAnswered.count() + chatEscalated.count() + chatFallback.count();
        return total == 0 ? 0 : chatAnswered.count() / total;
    }

    private double escalationRatio() {
        double total = chatAnswered.count() + chatEscalated.count() + chatFallback.count();
        return total == 0 ? 0 : chatEscalated.count() / total;
    }

    private static long number(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    private static double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }

    /** 一次聚合的结果；Gauge 读它，不在 scrape 时查库。 */
    public record Snapshot(long backlogOnline, long backlogField, double firstFixRate,
                           double slaRate, double misreportRate, double p95Seconds) {
        static Snapshot empty() {
            return new Snapshot(0, 0, 0, 0, 0, 0);
        }
    }
}
