package com.wherelee.cabinet.application.workorder;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.entity.BizWorkOrder;
import com.wherelee.cabinet.domain.entity.BizWorkOrderEvent;
import com.wherelee.cabinet.domain.enums.WorkOrderStatus;
import com.wherelee.cabinet.domain.enums.WorkOrderType;
import com.wherelee.cabinet.infrastructure.mapper.BizWorkOrderEventMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizWorkOrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工单流转（19B）。
 *
 * <p>三件事决定了它的写法，都不是风格偏好：
 * <ol>
 *   <li><b>开单幂等靠唯一索引</b>：巡检每轮都会看到同一个没关的门，如果靠“先查有没有”再插，
 *       并发下会写出两张。这里直接插，撞了 {@code uk_work_order_open} 就回读那张 ——
 *       索引才是最终裁判（与格口防超卖同一个手法）。</li>
 *   <li><b>“人处理的”与“事实自己消失的”必须分状态</b>：RESOLVED 是结论，AUTO_CLOSED 是留底。
 *       混写就等于把机器的动作算成人的功劳。</li>
 *   <li><b>派线下是“另开一张”，不是改状态</b>：线上为什么派人、线下到场做了什么，是两段独立历史，
 *       合成一条就互相覆盖。</li>
 * </ol>
 */
@Service
public class WorkOrderService {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderService.class);

    /** 队列侧常量：两条队列对应两个角色，不靠角色名硬猜。 */
    public static final String SIDE_ONLINE = "ONLINE";
    public static final String SIDE_FIELD = "FIELD";

    private final BizWorkOrderMapper orders;
    private final BizWorkOrderEventMapper events;
    /** 19D：升级时同时生成“必须打电话”待办与给客户的站内消息。 */
    private final com.wherelee.cabinet.application.notify.NoticeService notices;

    public WorkOrderService(BizWorkOrderMapper orders, BizWorkOrderEventMapper events,
                            com.wherelee.cabinet.application.notify.NoticeService notices) {
        this.orders = orders;
        this.events = events;
        this.notices = notices;
    }

    /**
     * 开单入参。<b>所有字段都是“当时看到的事实”</b>，不是引用：电话与格口编号必须快照，
     * 否则用户改绑手机之后，这张单就再也说不清当时联系的是谁。
     */
    public record NewOrder(Long tenantId, WorkOrderType type, boolean urgent,
                           Long siteId, Long cabinetId, Long slotId, String slotNo,
                           String orderNo, Long storageId, Long customerId, String customerPhone,
                           String siteAddress, String cabinetNo, String title, String detection,
                           LocalDateTime dueAt) {
    }

    /** 队列视图：接口层不回传实体（ArchUnit 会拦，而且实体带着一堆流转细节）。 */
    public record View(Long id, String type, boolean urgent, String status, String queueSide,
                       String slotNo, String cabinetNo, String siteAddress, String orderNo,
                       String customerPhone, String title, String detection, String resultNote,
                       Long assigneeId, LocalDateTime dueAt, LocalDateTime createTime) {
    }

    /** 留痕视图。 */
    public record EventView(String eventType, Long operatorId, String detail, LocalDateTime at) {
    }

    /**
     * 开单（幂等）。已存在同格口同类型的未关闭工单时，返回那张的 ID 而不新建，
     * 并把它“又被观察到一次”记进流水——这样队列长度始终是“有几件事要办”。
     */
    @Transactional
    public Long open(NewOrder cmd) {
        BizWorkOrder existing = findOpen(cmd.type(), cmd.slotId());
        if (existing != null) {
            note(existing, "REPEATED", null, "同一异常再次被观察到：" + cmd.detection());
            return existing.getId();
        }

        BizWorkOrder order = new BizWorkOrder();
        order.setTenantId(cmd.tenantId());
        order.setType(cmd.type());
        order.setUrgent(cmd.urgent());
        order.setStatus(WorkOrderStatus.PENDING);
        order.setQueueSide(SIDE_ONLINE);
        order.setActiveFlag(1);
        order.setSiteId(cmd.siteId());
        order.setCabinetId(cmd.cabinetId());
        order.setSlotId(cmd.slotId());
        order.setSlotNo(cmd.slotNo());
        order.setOrderNo(cmd.orderNo());
        order.setStorageId(cmd.storageId());
        order.setCustomerId(cmd.customerId());
        order.setTitle(cmd.title());
        order.setDueAt(cmd.dueAt());
        order.setSnapshotJson(snapshot(cmd));
        try {
            orders.insert(order);
        } catch (DuplicateKeyException e) {
            // 并发下另一个巡检/另一次上报先到：回读那张，不算失败也不重复建
            BizWorkOrder won = findOpen(cmd.type(), cmd.slotId());
            if (won == null) {
                // 冲突却查不到：唯一索引挡住了新单而那张已被关/软删，属于不一致现场，必须响
                throw new BizException(ResultCode.SYSTEM_ERROR,
                        "工单唯一索引冲突但找不到未关闭的那张，slotId=" + cmd.slotId());
            }
            note(won, "REPEATED", null, "并发开单合并到已有工单：" + cmd.title());
            return won.getId();
        }
        note(order, "CREATED", null, cmd.title() + "｜检测：" + nullSafe(cmd.detection()));
        log.warn("工单已开 id={} type={} urgent={} slotNo={} orderNo={}",
                order.getId(), cmd.type(), cmd.urgent(), cmd.slotNo(), cmd.orderNo());
        return order.getId();
    }

    /** 认领：抢不到就是别人已经接了，给业务异常而不静默成功。 */
    @Transactional
    public void claim(Long workOrderId, Long operatorId) {
        if (orders.claim(workOrderId, operatorId) == 0) {
            BizWorkOrder now = require(workOrderId);
            if (now.getStatus() == WorkOrderStatus.CLAIMED && operatorId.equals(now.getAssigneeId())) {
                return; // 重放友好：自己已经认领过，不报错也不重复写
            }
            throw new BizException(ResultCode.BIZ_ERROR,
                    "这张工单已被他人认领或已关闭，当前状态：" + now.getStatus());
        }
        note(require(workOrderId), "CLAIMED", operatorId, "已认领");
    }

    /**
     * 处理并反馈。{@code dispatchField=true} 时<b>另开一张线下工单</b>并把父单落 RESOLVED——
     * 顺序不能反：先派子单再关父单，中间崩了只会多一张待办，反过来会丢派单。
     *
     * @param needOffline 线上业务员的判断：“需不需要跑一趟”。用户口径里这是唯一派线下工单的依据
     */
    @Transactional
    public Long resolve(Long workOrderId, Long operatorId, String resultNote, boolean needOffline) {
        if (resultNote == null || resultNote.isBlank()) {
            throw new BizException(ResultCode.BIZ_ERROR, "处理结论必填：工单的价值就在“谁说了什么”");
        }
        BizWorkOrder order = require(workOrderId);
        if (orders.resolve(workOrderId, resultNote) == 0) {
            throw new BizException(ResultCode.BIZ_ERROR, "这张工单已关闭，不能重复处理");
        }
        note(order, "RESOLVED", operatorId, resultNote);
        if (!needOffline) {
            return null;
        }
        BizWorkOrder child = new BizWorkOrder();
        child.setTenantId(order.getTenantId());
        child.setType(order.getType());
        // 线下工单继承级别：紧急异常意味着“需要快速处理”（用户口径）
        child.setUrgent(order.getUrgent());
        child.setStatus(WorkOrderStatus.PENDING);
        child.setQueueSide(SIDE_FIELD);
        child.setActiveFlag(1);
        child.setParentId(order.getId());
        child.setSiteId(order.getSiteId());
        child.setCabinetId(order.getCabinetId());
        child.setSlotId(order.getSlotId());
        child.setSlotNo(order.getSlotNo());
        child.setOrderNo(order.getOrderNo());
        child.setStorageId(order.getStorageId());
        child.setCustomerId(order.getCustomerId());
        child.setTitle("线下到场：" + order.getTitle());
        child.setSnapshotJson(order.getSnapshotJson());
        child.setDueAt(LocalDateTime.now().plusHours(order.getUrgent() != null && order.getUrgent() ? 2 : 24));
        orders.insert(child);
        note(child, "DISPATCHED", operatorId, "线上判断需线下运维，父单 id=" + order.getId());
        log.warn("已派发线下工单 parent={} child={} urgent={}", order.getId(), child.getId(), child.getUrgent());
        return child.getId();
    }

    /**
     * 事实自行消失时的自动转化（门被关上、遗留被取走）。<b>不删除、不改写成人做的结论</b>，
     * 也不管它有没有被认领——没认领的更要留底，那正是“系统发现→自己好了”的样本。
     */
    @Transactional
    public int autoClose(Long slotId, WorkOrderType type) {
        int closed = orders.autoCloseBySlot(slotId, type.name());
        if (closed > 0) {
            List<BizWorkOrder> list = orders.selectList(Wrappers.<BizWorkOrder>lambdaQuery()
                    .eq(BizWorkOrder::getSlotId, slotId)
                    .eq(BizWorkOrder::getType, type)
                    .eq(BizWorkOrder::getStatus, WorkOrderStatus.AUTO_CLOSED)
                    .orderByDesc(BizWorkOrder::getClosedAt)
                    .last("limit " + closed));
            for (BizWorkOrder o : list) {
                note(o, "AUTO_CLOSED", null, "异常事实已消失，工单自动转化留底（非人工结论）");
            }
            log.info("工单自动转化留底 slotId={} type={} 条数={}", slotId, type, closed);
        }
        return closed;
    }

    /** 队列分页：紧急在前、开单时间在前的先办。 */
    public List<View> queue(String queueSide, boolean openOnly) {
        return orders.selectList(Wrappers.<BizWorkOrder>lambdaQuery()
                .eq(BizWorkOrder::getQueueSide, queueSide)
                .eq(BizWorkOrder::getDeleted, 0)
                .in(openOnly, BizWorkOrder::getStatus, WorkOrderStatus.PENDING, WorkOrderStatus.CLAIMED)
                .orderByDesc(BizWorkOrder::getUrgent)
                .orderByAsc(BizWorkOrder::getCreateTime)
                .last("limit 200")).stream().map(this::toView).toList();
    }

    public List<EventView> timeline(Long workOrderId) {
        return events.selectList(Wrappers.<BizWorkOrderEvent>lambdaQuery()
                .eq(BizWorkOrderEvent::getWorkOrderId, workOrderId)
                .orderByAsc(BizWorkOrderEvent::getCreateTime))
                .stream()
                .map(e -> new EventView(e.getEventType(), e.getOperatorId(), e.getDetail(), e.getCreateTime()))
                .toList();
    }

    /**
     * 记一次“被催办”（19D 阶梯）。返回 false 表示没有未关闭的对应工单（异常已收口）。
     *
     * <p>为什么不开一张 `biz_reminder` 表：提醒的语义就是“这张单又被催了一次”，
     * 它本来就是工单事件流水里的一行；另建一张表只会多出一个要同步的真相。
     * 真正的投递（站内消息、需打电话的任务）是另一件事，落在 `biz_notice` 上。
     */
    @Transactional
    public boolean remind(Long slotId, WorkOrderType type, String detail) {
        BizWorkOrder open = findOpen(type, slotId);
        if (open == null) {
            return false;
        }
        note(open, "REMINDER", null, detail);
        return true;
    }

    /**
     * 阶梯到点：升级为紧急并把处理时限拉到“就是现在”，同时产出两件可见的东西：
     * 业务员一条“必须电话联系客户”的待办，客户一条站内消息。
     *
     * <p>去重键用“工单 ID + 档位”而不是“时刻”：阶梯每轮巡检都会重进这里，
     * 用时刻做键就会每隔几分钟刷出一条一模一样的电话任务。
     */
    @Transactional
    public boolean escalate(Long slotId, WorkOrderType type, String detail) {
        if (orders.escalateBySlot(slotId, type.name()) == 0) {
            return false;
        }
        BizWorkOrder open = findOpen(type, slotId);
        if (open == null) {
            return true;
        }
        note(open, "ESCALATED", null, detail);
        String phone = phoneFromSnapshot(open);
        String where = (open.getSlotNo() == null ? "格口" : "格口 " + open.getSlotNo())
                + "（" + open.getTitle() + "）";
        // 业务员侧待办：不假装系统自己打了电话，只把“必须联系”挂在队列里并要求回填结果
        notices.emit(open.getTenantId(), com.wherelee.cabinet.application.notify.NoticeService.CHANNEL_PHONE,
                type.name(), "WO" + open.getId() + ":PHONE",
                "必须电话联系客户 " + (phone == null ? "（无电话记录）" : phone) + "：" + where + " — " + detail,
                open.getCustomerId(), open.getId(), open.getOrderNo());
        // 客户侧站内消息：没有推送，他自己打开小程序能看到也算提醒到了
        notices.emit(open.getTenantId(), com.wherelee.cabinet.application.notify.NoticeService.CHANNEL_MESSAGE,
                type.name(), "WO" + open.getId() + ":MSG",
                "你的暂存柜" + where + " 需要处理：" + detail + "。请尽快关门或取走物品并结束订单。",
                open.getCustomerId(), open.getId(), open.getOrderNo());
        log.warn("工单升级紧急 slotId={} type={} {}", slotId, type, detail);
        return true;
    }

    /**
     * 这张单已催过几次。阶梯靠它判重：一轮巡检可能跑两次（租约过期被接管），
     * 不数已发过多少就会在同一时刻重复发提醒。
     */
    public int remindedTimes(Long slotId, WorkOrderType type) {
        BizWorkOrder open = findOpen(type, slotId);
        if (open == null) {
            return 0;
        }
        return Math.toIntExact(events.selectCount(Wrappers.<BizWorkOrderEvent>lambdaQuery()
                .eq(BizWorkOrderEvent::getWorkOrderId, open.getId())
                .eq(BizWorkOrderEvent::getEventType, "REMINDER")));
    }

    /** 异常 → 工单映射所需的类型入口（阶梯升级时要升级同一类单）。 */
    public Long openIdOf(Long slotId, WorkOrderType type) {
        BizWorkOrder open = findOpen(type, slotId);
        return open == null ? null : open.getId();
    }

    /**
     * 按格口取当前绑定的活动单（阶梯里用来区分“订单中”与“结束后”的措辞）。 */
    public Long boundOrderId(Long slotId) {
        BizWorkOrder open = findOpen(WorkOrderType.DOOR_NOT_CLOSED, slotId);
        return open == null ? null : open.getStorageId();
    }

    private BizWorkOrder findOpen(WorkOrderType type, Long slotId) {
        return orders.selectOne(Wrappers.<BizWorkOrder>lambdaQuery()
                .eq(BizWorkOrder::getType, type)
                .eq(BizWorkOrder::getSlotId, slotId)
                .eq(BizWorkOrder::getActiveFlag, 1));
    }

    private BizWorkOrder require(Long id) {
        BizWorkOrder order = orders.selectById(id);
        if (order == null) {
            throw new BizException(ResultCode.BIZ_ERROR, "工单不存在：" + id);
        }
        return order;
    }

    private void note(BizWorkOrder order, String eventType, Long operatorId, String detail) {
        BizWorkOrderEvent event = new BizWorkOrderEvent();
        event.setTenantId(order.getTenantId());
        event.setWorkOrderId(order.getId());
        event.setEventType(eventType);
        event.setOperatorId(operatorId);
        event.setDetail(detail == null ? eventType : truncate(detail));
        event.setCreateTime(LocalDateTime.now());
        events.insert(event);
    }

    private String truncate(String text) {
        return text.length() <= 500 ? text : text.substring(0, 497) + "...";
    }

    /** 快照里放业务员判断性质需要的东西：位置、编号、时间、用户电话、检测值。 */
    private String snapshot(NewOrder cmd) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"siteAddress\":\"").append(escape(cmd.siteAddress())).append("\",");
        sb.append("\"cabinetNo\":\"").append(escape(cmd.cabinetNo())).append("\",");
        sb.append("\"slotNo\":\"").append(escape(cmd.slotNo())).append("\",");
        sb.append("\"orderNo\":\"").append(escape(cmd.orderNo())).append("\",");
        sb.append("\"customerPhone\":\"").append(escape(cmd.customerPhone())).append("\",");
        sb.append("\"detection\":\"").append(escape(cmd.detection())).append("\",");
        sb.append("\"openedAt\":\"").append(LocalDateTime.now()).append("\"");
        return sb.append("}").toString();
    }

    private String escape(String raw) {
        return raw == null ? "" : raw.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String nullSafe(String raw) {
        return raw == null ? "未测" : raw;
    }

    private View toView(BizWorkOrder o) {
        return new View(o.getId(), o.getType().name(), Boolean.TRUE.equals(o.getUrgent()),
                o.getStatus().name(), o.getQueueSide(), o.getSlotNo(), null, null, o.getOrderNo(),
                phoneFromSnapshot(o), o.getTitle(), null, o.getResultNote(), o.getAssigneeId(),
                o.getDueAt(), o.getCreateTime());
    }

    /**
     * 电话只从快照取（不回查客户表）：这张单要反映“当时拨的是哪个号”。
     *
     * <p><b>不能按 {@code "key":"} 拼精确位置</b>：MySQL 的 JSON 列存进去会被重排成
     * {@code "key": "..."}（冒号后多一个空格），按无空格的模式找就会永远返回 null。
     * 这个坑我在定价快照断言上已经踩过一次，这次是踩在生产代码里。
     * 所以改成：定位键名 → 找第一个冒号 → 取冒号后第一个引号里的内容。
     */
    private String phoneFromSnapshot(BizWorkOrder o) {
        String json = o.getSnapshotJson();
        if (json == null) {
            return null;
        }
        int key = json.indexOf("customerPhone");
        if (key < 0) {
            return null;
        }
        int colon = json.indexOf(':', key);
        int from = json.indexOf('"', colon + 1);
        if (colon < 0 || from < 0) {
            return null;
        }
        int to = json.indexOf('"', from + 1);
        return to < 0 ? null : json.substring(from + 1, to);
    }
}
