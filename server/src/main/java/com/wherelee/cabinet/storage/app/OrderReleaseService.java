package com.wherelee.cabinet.storage.app;

import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.OrderStatus;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import com.wherelee.cabinet.locker.api.Presence;
import com.wherelee.cabinet.locker.api.Sensing;
import com.wherelee.cabinet.locker.api.SlotAllocator;
import com.wherelee.cabinet.locker.api.SlotPoolPort;
import com.wherelee.cabinet.locker.api.SlotPort;
import com.wherelee.cabinet.storage.api.FundPort;
import com.wherelee.cabinet.storage.api.StorageOrderView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 释放一张不再计费的单：取消、上报遗留物、开门失败退单共用这一个出口。
 *
 * <p>为什么从订单用例里拆出来：{@code releaseOrder} 是“转取消 + 释放格口 + 解冻预收 + 同步空闲集合”
 * 四件事的顺序约定（先标异常/先释放，顺序反了就会把还放着东西的格子卖出去）。开柜会话在
 * 预备开失败后也要退单，如果它反向依赖订单用例就形成构造期循环；抽成无环的协作者，
 * 三处调用看到的是同一份顺序。
 */
@Service
public class OrderReleaseService {

    private static final Logger log = LoggerFactory.getLogger(OrderReleaseService.class);

    private final OrderLookup lookup;
    private final BizStorageOrderMapper orderMapper;
    private final SlotPoolPort slotPool;
    private final SlotPort states;
    private final FundPort funds;
    private final SlotAllocator allocator;

    public OrderReleaseService(OrderLookup lookup,
                               BizStorageOrderMapper orderMapper,
                               SlotPoolPort slotPool,
                               SlotPort states,
                               FundPort funds,
                               SlotAllocator allocator) {
        this.lookup = lookup;
        this.orderMapper = orderMapper;
        this.slotPool = slotPool;
        this.states = states;
        this.funds = funds;
        this.allocator = allocator;
    }

    /**
     * 用户主动取消：柜内有物就拦（与“上报遗留物”相反，那条路径正因为看到有物才上报）。
     */
    @Transactional
    public StorageOrderView cancel(Long customerId, String orderNo) {
        BizStorageOrder order = lookup.findOwned(customerId, orderNo);
        // 取消不能比结束更松：检测到柜内有东西时不得取消。否则就是“钱退了、件留下了、
        // 格子又卖给了第三个人”——那是一整个流程里最难收拾的现场（不变量 I12）。
        Sensing sensing = states.sense(order.getCabinetId(), order.getSlotId());
        if (sensing.presence() == Presence.PRESENT) {
            throw new BizException(ResultCode.BIZ_ERROR,
                    "柜内检测到物品：请先取出再取消（或走“声明放弃物品”结束）");
        }

        if (order.getToleranceUntil() != null && !emptyEvidence(sensing)) {
            // 门曾经开过（他可能已经放了东西进去）而柜内又确认不了：“取消”不能比“结束”拿到更少的凭据。
            // 不拦他取消（未计费阶段拦人无意义），但格口锁成待确认，等一次人来看看
            states.markContentUnverified(order.getSlotId(),
                    "开过门的单被取消且柜内无法确认，需现场确认后才能重新分配");
        }
        return releaseOrder(order);
    }

    /**
     * 把一张不再计费的单释放掉：转 CANCELLED + 释放格口 + 全额解冻 + 同步预扣集合。
     *
     * <p>两个入口共用它：<b>用户主动取消</b>与<b>上报遗留物后退单</b>。两者对柜内的态度相反
     * （前者“有物就拦”，后者正因为看到有物才上报），所以柜内检查留在 cancel() 里而不是下沉到这里——
     * 下沉了会把上报路径堵死，不下沉则会漏拦取消路径。
     */
    StorageOrderView releaseOrder(BizStorageOrder order) {
        order.transitTo(OrderStatus.CANCELLED);
        boolean released = states.releaseReserved(order.getSlotId(), order.getId());
        if (!released) {
            // 状态说该释放却释放不掉：说明格口已被他人改写，必须报错回滚而不是"取消成功但格口仍占着"
            throw new BizException(ResultCode.SYSTEM_ERROR, "格口状态与订单不一致，请联系运营处理");
        }
        funds.cancelHold(order);
        if (orderMapper.updateById(order) == 0) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "订单已被并发修改，请重试 orderNo=" + order.getOrderNo());
        }
        // 预扣策略下 DB 回 FREE 了就必须让 Redis 空闲集合也看到它，否则这个位置从此“谁也算不到”。
        // 失败只影响准入精度（少卖），不影响正确性，所以这里只告警不阻断取消。
        if ("prealloc".equals(allocator.strategy())) {
            try {
                slotPool.addFree(order.getCabinetId(), order.getSizeType(), order.getSlotId());
            } catch (RuntimeException e) {
                log.warn("格口已回 FREE 但 Redis 空闲集合同步失败（等校准修复）slotId={}", order.getSlotId(), e);
            }
        }
        return new StorageOrderView(order.getOrderNo(), lookup.cabinetNoOf(order.getCabinetId()),
                lookup.slotNoOf(order.getSlotId()), order.getSizeType().name(), order.getStatus().name(),
                0, allocator.strategy(), null);
    }

    /** 这条读数能不能当“柜内已空”的凭据用（坏了、离线、结论过期都算不能用）。 */
    private boolean emptyEvidence(Sensing sensing) {
        return sensing.presence() == Presence.ABSENT && states.fresh(sensing);
    }
}
