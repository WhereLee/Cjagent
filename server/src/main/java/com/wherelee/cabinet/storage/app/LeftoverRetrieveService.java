package com.wherelee.cabinet.storage.app;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.device.api.CommandAction;
import com.wherelee.cabinet.device.api.CommandOutcome;
import com.wherelee.cabinet.device.api.CommandPort;
import com.wherelee.cabinet.device.api.FaultType;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.locker.api.CabinetSnapshot;
import com.wherelee.cabinet.locker.api.CompartmentAnomaly;
import com.wherelee.cabinet.locker.api.Presence;
import com.wherelee.cabinet.locker.api.Sensing;
import com.wherelee.cabinet.locker.api.SlotPort;
import com.wherelee.cabinet.locker.api.SlotQueryPort;
import com.wherelee.cabinet.locker.api.SlotSnapshot;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.servicedesk.api.WorkOrderType;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 物主自助取回遗留物（第 21 刀，2026-10-13 用户定稿）。
 *
 * <p>把"东西在柜里"翻译成"物主能拿回去"的闭环：
 * 发现遗留物时已自动通知物主（{@link SlotPort#markContentLeft}），
 * 这里补上他回来之后的两个动作——开门取物、确认取完。
 *
 * <p><b>授权依据是既有口径</b>：已结束订单不再拥有开门权限，唯一例外是
 * "异常格口对原主人始终可开"。所以这里的校验闸比取件更严：
 * ①单是自己的且已终态；②格口当前异常<b>必须是 CONTENT_LEFT</b>
 * （待确认/门未关/传感器矛盾都不是"开门拿走就好"的事，一律不给开）；
 * ③格口没有被别的活动单占用。三条缺一不可。
 *
 * <p><b>全程 0 元</b>：单已结过账，没有计费引擎挂着，开门取回不重启任何计费；
 * 也不改订单状态——终态单就是终态，取回物这件事只记在格口、台账与工单流水上。
 */
@Service
public class LeftoverRetrieveService {

    private static final Logger log = LoggerFactory.getLogger(LeftoverRetrieveService.class);

    private final BizStorageOrderMapper orderMapper;
    /** 格口只读查询：本类不再直接读格口/柜机表。 */
    private final SlotQueryPort slots;
    /** 格口状态动作：门态、清异常、证据与台账都从这里走。 */
    private final SlotPort slotStates;
    private final CommandPort deviceCommands;
    private final com.wherelee.cabinet.servicedesk.api.WorkOrderPort workOrders;
    /** 收敛走显式短事务：探测（sense）是一次设备问答，绝不能握着数据库连接做。 */
    private final TransactionTemplate newTx;

    public LeftoverRetrieveService(BizStorageOrderMapper orderMapper, SlotQueryPort slots,
                                   SlotPort slotStates, CommandPort deviceCommands,
                                   com.wherelee.cabinet.servicedesk.api.WorkOrderPort workOrders,
                                   TransactionTemplate txTemplate) {
        this.orderMapper = orderMapper;
        this.slots = slots;
        this.slotStates = slotStates;
        this.deviceCommands = deviceCommands;
        this.workOrders = workOrders;
        // 与 StorageOrderService 同款：从容器里的 TransactionTemplate 取事务管理器，
        // 避免双数据源下直接注入 PlatformTransactionManager 的歧义
        this.newTx = new TransactionTemplate(txTemplate.getTransactionManager());
    }

    /**
     * 第一步：开门取物。
     *
     * <p>requestId 按"这张单第几次遗留取回开门"生成（与投件/取件的计数分开）：
     * 同一次尝试幂等，重试换新键。开门成功只记门态与证据（door_open_at 有值
     * 本身就让格子不可分配，双保险），柜机明确拒绝或没回话都不降级 CONTENT_LEFT——
     * 强锁只认"复检确认柜内已空"这一种解法。
     */
    public String openForRetrieval(Long customerId, String orderNo) {
        BizStorageOrder order = findOwnedTerminal(customerId, orderNo);
        SlotSnapshot slot = slots.slot(order.getSlotId()).orElse(null);
        if (slot == null || !CompartmentAnomaly.CONTENT_LEFT.name().equals(slot.anomaly())) {
            throw new BizException(ResultCode.BIZ_ERROR, "这个订单没有待取回的遗留物品");
        }
        if (slot.currentOrderId() != null) {
            throw new BizException(ResultCode.BIZ_ERROR, "这个格口正在被使用，请稍后再试或联系客服");
        }
        if (slot.doorOpen()) {
            // 上一次开门后门还没关：不用再开，直接取
            return "柜门已经开着：请直接取走你的物品并关好柜门，然后点\"我取完了\"";
        }
        CabinetSnapshot cabinet = slots.cabinet(order.getCabinetId()).orElse(null);
        if (cabinet == null) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "柜机信息缺失，请联系客服处理");
        }

        CommandOutcome outcome;
        try {
            outcome = deviceCommands.dispatch(cabinet.id(), order.getSlotId(), CommandAction.OPEN,
                    order.getId(), orderNo + ":LEFTOVER:" + (countAttempts(orderNo) + 1));
        } catch (RuntimeException offline) {
            // 柜机离线/通道异常从 dispatch 抛出，这里转成人话；格口保持锁定
            log.warn("遗留物取回开门下发失败 orderNo={}: {}", orderNo, offline.getMessage());
            throw new BizException(ResultCode.BIZ_ERROR, "柜机现在连不上，请稍后再试；问题持续请联系客服");
        }

        if (outcome.faultType() == FaultType.OPEN_FAILED) {
            // 柜机自己说开不了：东西还在里面，格口保持 CONTENT_LEFT 等人工路径，绝不回可售
            log.warn("遗留物取回开门被柜机明确拒绝 orderNo={} slotId={}", orderNo, order.getSlotId());
            return "这个柜口打不开：请稍后再试一次，或联系客服安排现场开门";
        }
        if (outcome.businessSuccess()) {
            slotStates.afterDoorOpened(order.getSlotId(), customerId);
            log.info("遗留物取回开门成功 orderNo={} slotId={} customer={}", orderNo, order.getSlotId(), customerId);
            return "柜门已打开，请取走你的物品并关好柜门，然后点\"我取完了\"";
        }
        // 无回执（超时/串位）：门可能开了也可能没开——不动格口、不退凭据，让用户确认现场
        return "已下发开门指令，请确认柜门是否打开；没打开请稍后再试或联系客服";
    }

    /**
     * 第二步：确认取完并收口。
     *
     * <p>复检（sense）在事务外做，收敛在短事务里——与门未关看管同一条纪律：
     * 探测是一次设备问答，不能握着数据库连接等它。
     *
     * <p>收口只认双凭据：物主本人操作 + 传感器确认柜内已空且结论新鲜。
     * 缺任何一个都保持锁定——这是"遗留物只能靠凭据解除"的既有不变量，
     * 区别只是这里的"人"就是物主本人，不用再等运维跑一趟。
     */
    public String confirmRetrieved(Long customerId, String orderNo) {
        BizStorageOrder order = findOwnedTerminal(customerId, orderNo);
        SlotSnapshot slot = slots.slot(order.getSlotId()).orElse(null);
        if (slot == null || !CompartmentAnomaly.CONTENT_LEFT.name().equals(slot.anomaly())) {
            throw new BizException(ResultCode.BIZ_ERROR, "这个订单没有待取回的遗留物品");
        }
        // 门关没关不看 DB 里的 door_open_at：物主没有“关门校验”入口，上一次开门留下的
        // door_open_at 只有等门磁说话才能收敛——这里的现场事实以 sense 为准，收敛在下方统一做
        Sensing sensing = slotStates.sense(order.getCabinetId(), order.getSlotId());
        if (!sensing.doorClosed()) {
            return "柜门还开着：请先关好柜门，再点\"我取完了\"";
        }
        if (sensing.presence() == Presence.PRESENT) {
            return "柜内检测到仍有物品：请确认是否全部取走，关好柜门后再点一次\"我取完了\"";
        }
        if (sensing.presence() != Presence.ABSENT || !slotStates.fresh(sensing)) {
            return "这次没能确认柜内已空（设备没给出可靠结论）：格口保持锁定，请联系客服安排现场确认";
        }
        // 门磁确认已关：把门态收敛掉（清 door_open_at、记关门证据）。
        // 复用关门收敛的唯一实现而不是另写一份——另写一份迟早出现“实时说关了、取回说没关”的两个真相
        slotStates.afterDoorClosed(order.getSlotId(), customerId);

        newTx.executeWithoutResult(status -> {
            if (!slotStates.clearAnomaly(order.getSlotId())) {
                throw new BizException(ResultCode.SYSTEM_ERROR, "格口状态刚刚被其他人处理，请刷新后重试");
            }
            slotStates.recordRetrievalEvidence(order.getSlotId(),
                    "物主自助取回复检：柜内已空，格口回可售（订单 " + orderNo + "）");
            slotStates.recordManualRecovery(order.getSlotId(),
                    "物主自助取回，复检柜内无物（订单 " + orderNo + "）");
            workOrders.noteStep(order.getSlotId(), WorkOrderType.ITEM_LEFT, "RETRIEVED_BY_OWNER",
                    "物主（客户 " + customerId + "）自助取回，复检柜内无物：格口已回可售；工单结论待业务员回填");
        });
        log.info("遗留物已被物主取回 orderNo={} slotId={} customer={}", orderNo, order.getSlotId(), customerId);
        return "已确认取完：柜门已关好、柜内已空，这个格口恢复使用。谢谢你";
    }

    private BizStorageOrder findOwnedTerminal(Long customerId, String orderNo) {
        BizStorageOrder order = orderMapper.selectOne(Wrappers.<BizStorageOrder>lambdaQuery()
                .eq(BizStorageOrder::getOrderNo, orderNo));
        if (order == null || !order.getCustomerId().equals(customerId)) {
            // 不区分"不存在"与"不是你的"：与全项目同一口径，堵单号枚举
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "寄存单不存在");
        }
        if (!order.getStatus().isTerminal()) {
            throw new BizException(ResultCode.BIZ_ERROR, "这个订单还没结束：进行中的单请走正常的开柜/取件流程");
        }
        return order;
    }

    private long countAttempts(String orderNo) {
        // “第几次尝试”由设备侧按 requestId 前缀计数
        return deviceCommands.countAttempts(orderNo + ":LEFTOVER:");
    }
}
