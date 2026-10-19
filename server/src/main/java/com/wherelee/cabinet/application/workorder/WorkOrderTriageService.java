package com.wherelee.cabinet.application.workorder;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.domain.entity.BizTriageRule;
import com.wherelee.cabinet.domain.enums.WorkOrderCategory;
import com.wherelee.cabinet.domain.enums.WorkOrderImpact;
import com.wherelee.cabinet.domain.enums.WorkOrderIntent;
import com.wherelee.cabinet.domain.enums.WorkOrderPriority;
import com.wherelee.cabinet.domain.enums.WorkOrderType;
import com.wherelee.cabinet.domain.enums.WorkOrderUrgency;
import com.wherelee.cabinet.infrastructure.mapper.BizTriageRuleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分诊：用户现象 + 事实 → 处置分类 / 队列 / 优先级 / SLA。
 *
 * <p>规则优先从 {@code biz_triage_rule} 读，按租户缓存；没有配置的租户用内置默认规则，
 * 这样新租户不会因为“没填规则”就无法建单。规则未命中一律落 CUSTOMER_SERVICE + UNSORTED，
 * 不硬派给线上或线下。
 */
@Service
public class WorkOrderTriageService {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderTriageService.class);

    private final BizTriageRuleMapper rules;
    private final Map<Long, CachedRules> cache = new ConcurrentHashMap<>();

    public WorkOrderTriageService(BizTriageRuleMapper rules) {
        this.rules = rules;
    }

    public record Facts(boolean deviceOffline, boolean cabinetImpact, boolean accountImpact, boolean urgent) {
        public static Facts none() {
            return new Facts(false, false, false, false);
        }
    }

    public record Result(WorkOrderCategory category, WorkOrderType type, String queueSide,
                         String assigneeGroup, WorkOrderPriority priority, WorkOrderImpact impact,
                         WorkOrderUrgency urgency, int responseMinutes, int resolveMinutes,
                         String ruleCode, int ruleVersion) {
    }

    public Result triage(WorkOrderIntent intent, Facts facts) {
        Map<WorkOrderIntent, Rule> loaded = rulesForCurrentTenant();
        Rule rule = loaded.getOrDefault(intent, unsorted(intent));
        // 事实优先于规则默认值：柜机离线时，“门打不开/关不上、取件码”不是单格门故障，
        // 按整柜设备故障处置——否则会派一个人去修一扇门，而整柜根本连不上。
        if (facts != null && facts.deviceOffline() && needsOnlineDevice(intent)) {
            rule = loaded.getOrDefault(WorkOrderIntent.DEVICE_OFFLINE,
                    defaultRules().get(WorkOrderIntent.DEVICE_OFFLINE));
        }
        WorkOrderPriority priority = rule.priority();
        WorkOrderImpact impact = rule.impact();
        // 整柜/账户级问题直接拉到 P1；紧急单至少 P2。
        if (facts != null && (facts.deviceOffline() || facts.cabinetImpact())) {
            impact = WorkOrderImpact.CABINET;
            priority = WorkOrderPriority.P1;
        } else if (facts != null && facts.accountImpact()) {
            impact = WorkOrderImpact.ACCOUNT;
            priority = WorkOrderPriority.P1;
        } else if (facts != null && facts.urgent()
                && (priority == WorkOrderPriority.P3 || priority == WorkOrderPriority.P4)) {
            priority = WorkOrderPriority.P2;
        }
        return new Result(rule.category(), rule.type(), rule.queueSide(), rule.assigneeGroup(),
                priority, impact, rule.urgency(), rule.responseMinutes(), rule.resolveMinutes(),
                rule.ruleCode(), rule.ruleVersion());
    }

    /** 这些现象都要靠柜机在线才能自助完成；柜机离线时改按设备故障处置。 */
    private static boolean needsOnlineDevice(WorkOrderIntent intent) {
        return intent == WorkOrderIntent.DOOR_NOT_OPEN
                || intent == WorkOrderIntent.DOOR_NOT_CLOSE
                || intent == WorkOrderIntent.PICKUP_CODE;
    }

    /** 管理端改完规则后调用：清缓存，下一次请求重新装载。 */
    public void reload() {
        cache.clear();
        log.info("分诊规则缓存已清理，下一次请求重新装载");
    }

    private Map<WorkOrderIntent, Rule> rulesForCurrentTenant() {
        Long tenantId = TenantContext.current();
        long key = tenantId == null ? 0L : tenantId;
        CachedRules cached = cache.get(key);
        if (cached != null) {
            return cached.rules();
        }
        List<BizTriageRule> rows = rules.selectList(Wrappers.<BizTriageRule>lambdaQuery()
                .eq(BizTriageRule::getEnabled, true)
                .orderByDesc(BizTriageRule::getVersion));
        Map<WorkOrderIntent, Rule> loaded = new EnumMap<>(WorkOrderIntent.class);
        int maxVersion = 0;
        for (BizTriageRule row : rows) {
            if (row.getIntent() == null || row.getCategory() == null) {
                continue;
            }
            loaded.putIfAbsent(row.getIntent(), new Rule(
                    row.getRuleCode(), row.getIntent(), row.getCategory(), typeOf(row.getCategory()),
                    row.getQueueSide() == null ? "ONLINE" : row.getQueueSide(),
                    row.getAssigneeGroup(), row.getPriority() == null ? WorkOrderPriority.P3 : row.getPriority(),
                    row.getImpact() == null ? WorkOrderImpact.SINGLE : row.getImpact(),
                    row.getUrgency() == null ? WorkOrderUrgency.NORMAL : row.getUrgency(),
                    row.getResponseMinutes() == null ? 120 : row.getResponseMinutes(),
                    row.getResolveMinutes() == null ? 1440 : row.getResolveMinutes(),
                    row.getVersion() == null ? 0 : row.getVersion()));
            maxVersion = Math.max(maxVersion, row.getVersion() == null ? 0 : row.getVersion());
        }
        if (loaded.isEmpty()) {
            loaded = defaultRules();
        }
        Map<WorkOrderIntent, Rule> immutable = Map.copyOf(loaded);
        cache.put(key, new CachedRules(immutable, maxVersion));
        return immutable;
    }

    private static Rule unsorted(WorkOrderIntent intent) {
        return new Rule("DEFAULT-UNSORTED", intent, WorkOrderCategory.UNSORTED, null,
                "CUSTOMER_SERVICE", "CUSTOMER_SERVICE", WorkOrderPriority.P3,
                WorkOrderImpact.SINGLE, WorkOrderUrgency.NORMAL, 120, 1440, 0);
    }

    private static Map<WorkOrderIntent, Rule> defaultRules() {
        Map<WorkOrderIntent, Rule> map = new EnumMap<>(WorkOrderIntent.class);
        put(map, WorkOrderIntent.DOOR_NOT_OPEN, WorkOrderCategory.DOOR_FAULT,
                "ONLINE", "ONLINE", WorkOrderPriority.P2, WorkOrderImpact.SINGLE,
                WorkOrderUrgency.HIGH, 30, 120);
        put(map, WorkOrderIntent.DOOR_NOT_CLOSE, WorkOrderCategory.DOOR_NOT_CLOSED,
                "ONLINE", "ONLINE", WorkOrderPriority.P1, WorkOrderImpact.SINGLE,
                WorkOrderUrgency.HIGH, 10, 120);
        put(map, WorkOrderIntent.ITEM_MISSING, WorkOrderCategory.ITEM_DISPUTE,
                "ONLINE", "ONLINE", WorkOrderPriority.P1, WorkOrderImpact.SINGLE,
                WorkOrderUrgency.HIGH, 10, 120);
        put(map, WorkOrderIntent.ITEM_LEFT, WorkOrderCategory.ITEM_LEFT,
                "ONLINE", "ONLINE", WorkOrderPriority.P2, WorkOrderImpact.SINGLE,
                WorkOrderUrgency.NORMAL, 30, 240);
        put(map, WorkOrderIntent.DEVICE_OFFLINE, WorkOrderCategory.DEVICE_OFFLINE,
                "ONLINE", "ONLINE", WorkOrderPriority.P1, WorkOrderImpact.CABINET,
                WorkOrderUrgency.HIGH, 10, 120);
        put(map, WorkOrderIntent.BILLING_DISPUTE, WorkOrderCategory.BILLING_DISPUTE,
                "ONLINE", "ONLINE", WorkOrderPriority.P2, WorkOrderImpact.ACCOUNT,
                WorkOrderUrgency.HIGH, 30, 240);
        put(map, WorkOrderIntent.PICKUP_CODE, WorkOrderCategory.PICKUP_CODE,
                "ONLINE", "ONLINE", WorkOrderPriority.P2, WorkOrderImpact.SINGLE,
                WorkOrderUrgency.NORMAL, 30, 240);
        put(map, WorkOrderIntent.OTHER, WorkOrderCategory.UNSORTED,
                "CUSTOMER_SERVICE", "CUSTOMER_SERVICE", WorkOrderPriority.P3,
                WorkOrderImpact.SINGLE, WorkOrderUrgency.NORMAL, 120, 1440);
        return map;
    }

    private static void put(Map<WorkOrderIntent, Rule> map, WorkOrderIntent intent,
                            WorkOrderCategory category, String queueSide, String group,
                            WorkOrderPriority priority, WorkOrderImpact impact,
                            WorkOrderUrgency urgency, int responseMinutes, int resolveMinutes) {
        map.put(intent, new Rule("DEFAULT-" + intent.name(), intent, category, typeOf(category),
                queueSide, group, priority, impact, urgency, responseMinutes, resolveMinutes, 0));
    }

    /**
     * REQUEST 单仍需一个兼容 type 参与同队列唯一索引；category 仍是真正的处置分类。
     * UNSORTED 没有兼容 type，允许同一格口存在多张待人工分拣的请求。
     */
    private static WorkOrderType typeOf(WorkOrderCategory category) {
        return switch (category) {
            case DOOR_FAULT, DEVICE_OFFLINE, BILLING_DISPUTE, PICKUP_CODE -> WorkOrderType.DEVICE_FAULT;
            case DOOR_NOT_CLOSED -> WorkOrderType.DOOR_NOT_CLOSED;
            case ITEM_DISPUTE, ITEM_LEFT -> WorkOrderType.ITEM_LEFT;
            case UNSORTED -> null;
        };
    }

    private record Rule(String ruleCode, WorkOrderIntent intent, WorkOrderCategory category,
                        WorkOrderType type, String queueSide, String assigneeGroup,
                        WorkOrderPriority priority, WorkOrderImpact impact,
                        WorkOrderUrgency urgency, int responseMinutes, int resolveMinutes,
                        int ruleVersion) {
    }

    private record CachedRules(Map<WorkOrderIntent, Rule> rules, int version) {
    }
}
