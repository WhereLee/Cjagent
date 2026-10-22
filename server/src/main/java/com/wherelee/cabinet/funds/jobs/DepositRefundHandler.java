package com.wherelee.cabinet.funds.jobs;

import com.wherelee.cabinet.infrastructure.task.TaskOrders;
import com.wherelee.cabinet.platform.scheduling.TaskHandler;
import com.wherelee.cabinet.storage.api.FundPort;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.entity.BizDelayTask;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.domain.enums.TaskType;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 押金悬挂安全网（不变量 4）：订单已终态而押金还挂着，就把它退回去。
 *
 * <p><b>它现在只服务存量单</b>：定-1 把“每单押金”换成了“预收 4 小时”，新单不再写
 * {@code biz_deposit}，所以正常路径不会再产生悬挂。但表、押金栏与本 handler 都保留着——
 * 存量单还没走完，删了它们那些单就永远退不出钱（同理，删押金流水枚举会让旧账读不出来、
 * 删账户押金栏会让对账不变量算不平）。
 *
 * <p>“什么算悬挂”的判据不在这里另写一份：对账侧是 {@code ReconcileService.HANGING_DEPOSIT}，
 * 退钱侧是 {@code FundPort.refundHangingDeposit}，本类只负责“到点去叫它”。
 *
 * <p>未终态时<b>抛异常而不是返回 false</b>：抛了才会进退避重试，等单走到终态再处理；
 * 返回 false 会被调度当成“这件事做完了”，那笔押金就再也没人来退。
 */
@Component
public class DepositRefundHandler implements TaskHandler {

    private static final Logger log = LoggerFactory.getLogger(DepositRefundHandler.class);

    private final BizStorageOrderMapper orderMapper;
    private final FundPort funds;

    DepositRefundHandler(BizStorageOrderMapper orderMapper, FundPort funds) {
        this.orderMapper = orderMapper;
        this.funds = funds;
    }

    @Override
    public TaskType type() {
        return TaskType.DEPOSIT_REFUND;
    }

    @Override
    public boolean handle(BizDelayTask task) {
        BizStorageOrder order = TaskOrders.find(orderMapper, task.getBizKey());
        if (order == null) {
            return false;
        }
        if (!order.getStatus().isTerminal()) {
            // 还没结束，钱本来就该继续冻着：重投到终态之后再处理，而不是判死
            throw new BizException(ResultCode.BIZ_ERROR, "订单未终态，稍后再试 orderNo=" + order.getOrderNo());
        }
        int refunded = funds.refundHangingDeposit(order);
        if (refunded > 0) {
            log.warn("押金悬挂已回收 orderNo={} 退还点数={}", order.getOrderNo(), refunded);
        }
        return false;
    }
}
