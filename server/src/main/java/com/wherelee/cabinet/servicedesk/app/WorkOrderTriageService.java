package com.wherelee.cabinet.servicedesk.app;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.servicedesk.domain.BizTriageRule;
import com.wherelee.cabinet.servicedesk.api.WorkOrderCategory;
import com.wherelee.cabinet.servicedesk.api.WorkOrderImpact;
import com.wherelee.cabinet.servicedesk.api.WorkOrderIntent;
import com.wherelee.cabinet.servicedesk.api.WorkOrderPriority;
import com.wherelee.cabinet.servicedesk.api.WorkOrderType;
import com.wherelee.cabinet.servicedesk.api.WorkOrderUrgency;
import com.wherelee.cabinet.servicedesk.infra.BizTriageRuleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WorkOrderTriageService implements com.wherelee.cabinet.servicedesk.api.WorkOrderTriagePort {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderTriageService.class);

    private final BizTriageRuleMapper rules;
    private final WorkOrderPriorityPolicy priorityPolicy;
    private final WorkOrderSlaPolicy slaPolicy;
    private final Map<Long, CachedRules> cache = new ConcurrentHashMap<>();

    public WorkOrderTriageService(BizTriageRuleMapper rules, WorkOrderPriorityPolicy priorityPolicy,
                                  WorkOrderSlaPolicy slaPolicy) {
        this.rules = rules;
        this.priorityPolicy = priorityPolicy;
        this.slaPolicy = slaPolicy;
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
        if (facts != null && facts.deviceOffline() && needsOnlineDevice(intent)) {
            rule = loaded.getOrDefault(WorkOrderIntent.DEVICE_OFFLINE,
                    defaultRules().get(WorkOrderIntent.DEVICE_OFFLINE));
        }
        WorkOrderImpact impact = rule.impact();
        WorkOrderUrgency urgency = rule.urgency();
        if (facts != null && (facts.deviceOffline() || facts.cabinetImpact())) {
            impact = WorkOrderImpact.CABINET;
            urgency = WorkOrderUrgency.HIGH;
        } else if (facts != null && facts.accountImpact()) {
            impact = WorkOrderImpact.ACCOUNT;
            urgency = WorkOrderUrgency.HIGH;
        } else if (facts != null && facts.urgent()) {
            urgency = WorkOrderUrgency.HIGH;
        }
        WorkOrderPriority priority = rule.priority() == null
                ? priorityPolicy.of(impact, urgency) : rule.priority();
        if (facts != null && (facts.deviceOffline() || facts.cabinetImpact() || facts.accountImpact())) {
            priority = WorkOrderPriority.P1;
        } else if (facts != null && facts.urgent()
                && (priority == WorkOrderPriority.P3 || priority == WorkOrderPriority.P4)) {
            priority = WorkOrderPriority.P2;
        }
        int responseMinutes = rule.responseMinutes() == null
                ? slaPolicy.responseMinutes(priority) : rule.responseMinutes();
        int resolveMinutes = rule.resolveMinutes() == null
                ? slaPolicy.resolveMinutes(priority) : rule.resolveMinutes();
        return new Result(rule.category(), rule.type(), rule.queueSide(), rule.assigneeGroup(),
                priority, impact, urgency, responseMinutes, resolveMinutes,
                rule.ruleCode(), rule.ruleVersion());
    }

    private static boolean needsOnlineDevice(WorkOrderIntent intent) {
        return intent == WorkOrderIntent.DOOR_NOT_OPEN
                || intent == WorkOrderIntent.DOOR_NOT_CLOSE
                || intent == WorkOrderIntent.PICKUP_CODE;
    }

    @Override
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
                    row.getAssigneeGroup(), row.getPriority(),
                    row.getImpact() == null ? WorkOrderImpact.SINGLE : row.getImpact(),
                    row.getUrgency() == null ? WorkOrderUrgency.NORMAL : row.getUrgency(),
                    row.getResponseMinutes(), row.getResolveMinutes(),
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
                "CUSTOMER_SERVICE", "CUSTOMER_SERVICE", null,
                WorkOrderImpact.SINGLE, WorkOrderUrgency.NORMAL, null, null, 0);
    }

    private static Map<WorkOrderIntent, Rule> defaultRules() {
        Map<WorkOrderIntent, Rule> map = new EnumMap<>(WorkOrderIntent.class);
        put(map, WorkOrderIntent.DOOR_NOT_OPEN, WorkOrderCategory.DOOR_FAULT,
                "ONLINE", "ONLINE", WorkOrderPriority.P2, WorkOrderImpact.SINGLE,
                WorkOrderUrgency.HIGH);
        put(map, WorkOrderIntent.DOOR_NOT_CLOSE, WorkOrderCategory.DOOR_NOT_CLOSED,
                "ONLINE", "ONLINE", WorkOrderPriority.P1, WorkOrderImpact.SINGLE,
                WorkOrderUrgency.HIGH);
        put(map, WorkOrderIntent.ITEM_MISSING, WorkOrderCategory.ITEM_DISPUTE,
                "ONLINE", "ONLINE", WorkOrderPriority.P1, WorkOrderImpact.SINGLE,
                WorkOrderUrgency.HIGH);
        put(map, WorkOrderIntent.ITEM_LEFT, WorkOrderCategory.ITEM_LEFT,
                "ONLINE", "ONLINE", WorkOrderPriority.P2, WorkOrderImpact.SINGLE,
                WorkOrderUrgency.NORMAL);
        put(map, WorkOrderIntent.DEVICE_OFFLINE, WorkOrderCategory.DEVICE_OFFLINE,
                "ONLINE", "ONLINE", WorkOrderPriority.P1, WorkOrderImpact.CABINET,
                WorkOrderUrgency.HIGH);
        put(map, WorkOrderIntent.BILLING_DISPUTE, WorkOrderCategory.BILLING_DISPUTE,
                "ONLINE", "ONLINE", WorkOrderPriority.P2, WorkOrderImpact.ACCOUNT,
                WorkOrderUrgency.HIGH);
        put(map, WorkOrderIntent.PICKUP_CODE, WorkOrderCategory.PICKUP_CODE,
                "ONLINE", "ONLINE", WorkOrderPriority.P2, WorkOrderImpact.SINGLE,
                WorkOrderUrgency.NORMAL);
        put(map, WorkOrderIntent.OTHER, WorkOrderCategory.UNSORTED,
                "CUSTOMER_SERVICE", "CUSTOMER_SERVICE", WorkOrderPriority.P3,
                WorkOrderImpact.SINGLE, WorkOrderUrgency.NORMAL);
        return map;
    }

    private static void put(Map<WorkOrderIntent, Rule> map, WorkOrderIntent intent,
                            WorkOrderCategory category, String queueSide, String group,
                            WorkOrderPriority priority, WorkOrderImpact impact,
                            WorkOrderUrgency urgency) {
        map.put(intent, new Rule("DEFAULT-" + intent.name(), intent, category, typeOf(category),
                queueSide, group, priority, impact, urgency, null, null, 0));
    }

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
                        WorkOrderUrgency urgency, Integer responseMinutes, Integer resolveMinutes,
                        int ruleVersion) {
    }

    private record CachedRules(Map<WorkOrderIntent, Rule> rules, int version) {
    }
}
