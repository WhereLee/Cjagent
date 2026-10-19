package com.wherelee.cabinet.application.workorder;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.domain.entity.BizCabinet;
import com.wherelee.cabinet.domain.entity.BizCompartment;
import com.wherelee.cabinet.domain.entity.BizCustomer;
import com.wherelee.cabinet.domain.entity.BizSite;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.CompartmentAnomaly;
import com.wherelee.cabinet.domain.enums.Presence;
import com.wherelee.cabinet.domain.enums.WorkOrderCategory;
import com.wherelee.cabinet.domain.enums.WorkOrderImpact;
import com.wherelee.cabinet.domain.enums.WorkOrderIntent;
import com.wherelee.cabinet.domain.enums.WorkOrderKind;
import com.wherelee.cabinet.domain.enums.WorkOrderPriority;
import com.wherelee.cabinet.domain.enums.WorkOrderSource;
import com.wherelee.cabinet.domain.enums.WorkOrderType;
import com.wherelee.cabinet.domain.enums.WorkOrderUrgency;
import com.wherelee.cabinet.infrastructure.mapper.BizCabinetMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizCompartmentMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizCustomerMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizSiteMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 开单入口（19B）：把“格口被标了异常”翻译成“有人该管这件事”。
 *
 * <p>只挂在一个地方（异常标记的唯一出口），不在各业务分支里到处调 ——
 * 分散调用一定会漏掉某条路径，而漏掉的那条恰好是异常路径（这正是本项目反复踩过的形状：
 * “台账数得出来、没人被指向它”）。
 *
 * <p>位置、柜机编号、格口编号、用户电话都在这里补齐并快照。调用方只说“哪个格口、什么异常、为什么”，
 * 抄一份现场的责任集中在这里，避免每个分支各抄一次、各漏一处。
 */
@Component
public class WorkOrderIntake {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderIntake.class);

    private final WorkOrderService workOrders;
    private final BizCompartmentMapper slotMapper;
    private final BizCabinetMapper cabinetMapper;
    private final BizSiteMapper siteMapper;
    private final BizCustomerMapper customerMapper;
    private final BizStorageOrderMapper orderMapper;

    public WorkOrderIntake(WorkOrderService workOrders, BizCompartmentMapper slotMapper,
                           BizCabinetMapper cabinetMapper, BizSiteMapper siteMapper,
                           BizCustomerMapper customerMapper, BizStorageOrderMapper orderMapper) {
        this.workOrders = workOrders;
        this.slotMapper = slotMapper;
        this.cabinetMapper = cabinetMapper;
        this.siteMapper = siteMapper;
        this.customerMapper = customerMapper;
        this.orderMapper = orderMapper;
    }

    /**
     * 异常 → 工单。映射规则写在一处，方便你后面改口径时只动一张表：
     * <ul>
     *   <li>门未关：普通异常；<b>但柜内检测到有物就升紧急</b>（你的原话：紧急异常需快速处理）；</li>
     *   <li>遗留物 / 测不准：都算紧急 —— 前者可能贵重，后者根本没有凭据可判；</li>
     *   <li>传感器矛盾：设备故障，按普通排队（它不影响钱，只影响判断可信度）。</li>
     * </ul>
     *
     * <p>{@code dueAt}：门未关按你定的“10 分钟必须处理”，其余给 2 小时。
     * 这只是**要求时刻**，不是自动动作——到点该由谁被提醒属于 19D。
     */
    public void onAnomaly(Long slotId, CompartmentAnomaly anomaly, String reason) {
        BizCompartment slot = slotMapper.selectById(slotId);
        if (slot == null) {
            log.warn("标异常却查不到格口，工单跳过 slotId={} anomaly={}", slotId, anomaly);
            return;
        }
        WorkOrderType type;
        boolean urgent;
        switch (anomaly) {
            case DOOR_OPEN -> {
                type = WorkOrderType.DOOR_NOT_CLOSED;
                urgent = slot.getPresence() == Presence.PRESENT;
            }
            case CONTENT_LEFT -> {
                type = WorkOrderType.ITEM_LEFT;
                urgent = true;
            }
            case CONTENT_UNVERIFIED -> {
                // 门关了却拿不到“柜内已空”的凭据：最需要人来判，按紧急排队
                type = WorkOrderType.ITEM_LEFT;
                urgent = true;
            }
            case SENSOR_CONFLICT -> {
                type = WorkOrderType.DEVICE_FAULT;
                urgent = false;
            }
            // FAULT/MAINTENANCE 之类是硬件与人工停用，不属于“异常事件工单”这条流
            default -> {
                return;
            }
        }

        BizCabinet cabinet = slot.getCabinetId() == null ? null : cabinetMapper.selectById(slot.getCabinetId());
        BizSite site = cabinet == null || cabinet.getSiteId() == null
                ? null : siteMapper.selectById(cabinet.getSiteId());
        // 绑在这个格口上的活动单（可能没有：空格的传感器矛盾就没有物主）
        BizStorageOrder order = slot.getCurrentOrderId() == null
                ? null : orderMapper.selectById(slot.getCurrentOrderId());
        // 客户回退路径：单子一旦结算完成，格口上的 `current_order_id` 就被抹掉了，
        // 而“门未关/结束后遗留”这类异常往往正是在那之后才标上的——只认绑定关系就会开出
        // 一张**没有电话、拨不了也发不出消息**的紧急工单（审计报告缺陷 13）。
        // “是谁把这格打开的”就是当下用这一格的人，这个事实格口自己记着。
        Long customerId = order != null ? order.getCustomerId() : slot.getDoorOpenedBy();
        BizCustomer customer = customerId == null ? null : customerMapper.selectById(customerId);

        LocalDateTime dueAt = LocalDateTime.now().plusMinutes(urgent ? 10 : 120);
        workOrders.open(new WorkOrderService.NewOrderV2(
                slot.getTenantId(), WorkOrderKind.INCIDENT, type, WorkOrderSource.DEVICE,
                intentOf(anomaly), categoryOf(type), urgent,
                urgent ? WorkOrderPriority.P1 : WorkOrderPriority.P3,
                WorkOrderImpact.SINGLE,
                urgent ? WorkOrderUrgency.HIGH : WorkOrderUrgency.NORMAL,
                WorkOrderService.SIDE_ONLINE,
                site == null ? null : site.getId(), cabinet == null ? null : cabinet.getId(),
                slot.getId(), slot.getSlotNo(),
                order == null ? null : order.getOrderNo(), order == null ? null : order.getId(),
                customer == null ? null : customer.getId(), customer == null ? null : customer.getPhone(),
                site == null ? "未知点位" : site.getName(),
                cabinet == null ? "-" : cabinet.getCabinetNo(),
                titleOf(anomaly, slot.getSlotNo(), reason),
                detection(slot),
                dueAt, dueAt, dueAt, null, null));
    }

    /**
     * 按格口开一张“超封顶未结束”的单（19D 第 3 条）。
     *
     * <p>调度处理器手里只有单与格口，让它自己拼地址/编号/电话就得每个分支各抄一份；
     * 这里统一补齐，也让开单幂等只依赖一个入口。
     */
    public void longRunningFor(Long slotId, String reason) {
        BizCompartment slot = slotId == null ? null : slotMapper.selectById(slotId);
        BizStorageOrder order = slot == null || slot.getCurrentOrderId() == null
                ? null : orderMapper.selectById(slot.getCurrentOrderId());
        if (order == null) {
            log.warn("超封顶开单跳过：格口上没有活动单 slotId={}", slotId);
            return;
        }
        onOrderEvent(order, WorkOrderType.LONG_RUNNING, false, "计费已到 48 小时封顶", reason,
                java.time.LocalDateTime.now().plusHours(2));
    }

    /**
     * 直接开一张（超封顶未结束这类没有“异常标记”事件的场景）。
     *
     * <p>返回工单 ID：新建的返回新 ID，幂等合并的返回已存在那张的 ID。
     * AI 客服转报建单（第 22 刀）靠它把单号回给用户，其余调用方不关心返回值，不受影响。
     */
    public Long onOrderEvent(BizStorageOrder order, WorkOrderType type, boolean urgent,
                             String detection, String title, LocalDateTime dueAt) {
        return onOrderEvent(order, type, urgent, detection, title, dueAt,
                WorkOrderKind.INCIDENT, WorkOrderSource.SYSTEM, null, null, null, null, null);
    }

    /** 智能工单入口：AI 转报带 intent/handoff，系统任务仍可走上面的旧签名。 */
    public Long onOrderEvent(BizStorageOrder order, WorkOrderType type, boolean urgent,
                             String detection, String title, LocalDateTime dueAt,
                             WorkOrderKind kind, WorkOrderSource source, WorkOrderIntent intent,
                             WorkOrderCategory category, WorkOrderPriority priority, String handoffJson,
                             String traceId) {
        BizCompartment slot = order.getSlotId() == null ? null : slotMapper.selectById(order.getSlotId());
        BizCabinet cabinet = slot == null || slot.getCabinetId() == null
                ? null : cabinetMapper.selectById(slot.getCabinetId());
        BizSite site = cabinet == null ? null : siteMapper.selectById(cabinet.getSiteId());
        BizCustomer customer = order.getCustomerId() == null
                ? null : customerMapper.selectById(order.getCustomerId());
        return workOrders.open(new WorkOrderService.NewOrderV2(
                order.getTenantId(), kind, type, source, intent, category, urgent,
                priority == null ? (urgent ? WorkOrderPriority.P1 : WorkOrderPriority.P3) : priority,
                WorkOrderImpact.SINGLE,
                urgent ? WorkOrderUrgency.HIGH : WorkOrderUrgency.NORMAL,
                WorkOrderService.SIDE_ONLINE,
                site == null ? null : site.getId(), cabinet == null ? null : cabinet.getId(),
                order.getSlotId(), slot == null ? null : slot.getSlotNo(),
                order.getOrderNo(), order.getId(), order.getCustomerId(),
                customer == null ? null : customer.getPhone(),
                site == null ? "未知点位" : site.getName(),
                cabinet == null ? "-" : cabinet.getCabinetNo(),
                title, detection, dueAt, dueAt, dueAt, handoffJson, traceId));
    }

    private WorkOrderIntent intentOf(CompartmentAnomaly anomaly) {
        return switch (anomaly) {
            case DOOR_OPEN -> WorkOrderIntent.DOOR_NOT_CLOSE;
            case CONTENT_LEFT, CONTENT_UNVERIFIED -> WorkOrderIntent.ITEM_LEFT;
            case SENSOR_CONFLICT -> WorkOrderIntent.OTHER;
            default -> WorkOrderIntent.OTHER;
        };
    }

    private WorkOrderCategory categoryOf(WorkOrderType type) {
        return switch (type) {
            case DOOR_NOT_CLOSED -> WorkOrderCategory.DOOR_NOT_CLOSED;
            case ITEM_LEFT -> WorkOrderCategory.ITEM_LEFT;
            case DEVICE_FAULT -> WorkOrderCategory.DOOR_FAULT;
            case LONG_RUNNING -> WorkOrderCategory.UNSORTED;
        };
    }

    /** 检测值快照：红外/重量/AI 的结论在这里合并成一行可读文本（19C 补上照片记录与 AI 标签）。 */
    private String detection(BizCompartment slot) {
        return "门=" + (slot.doorOpen() ? "开着" : "已关")
                + "；柜内=" + presenceText(slot.getPresence())
                + "；异常=" + slot.getAnomaly();
    }

    private String presenceText(Presence presence) {
        if (presence == null) {
            return "未测";
        }
        return switch (presence) {
            case PRESENT -> "有物";
            case ABSENT -> "无物";
            case UNKNOWN -> "测不准";
        };
    }

    private String titleOf(CompartmentAnomaly anomaly, String slotNo, String reason) {
        String who = slotNo == null ? "格口" : "格口 " + slotNo;
        return switch (anomaly) {
            case DOOR_OPEN -> who + " 门未关：" + reason;
            case CONTENT_LEFT -> who + " 柜内有遗留物：" + reason;
            case CONTENT_UNVERIFIED -> who + " 无法确认已清空：" + reason;
            case SENSOR_CONFLICT -> who + " 传感器结论矛盾：" + reason;
            default -> who + " 异常：" + reason;
        };
    }
}
