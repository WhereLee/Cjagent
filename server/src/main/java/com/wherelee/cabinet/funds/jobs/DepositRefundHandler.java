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
            throw new BizException(ResultCode.BIZ_ERROR, "订单未终态，稍后再试 orderNo=" + order.getOrderNo());
        }
        int refunded = funds.refundHangingDeposit(order);
        if (refunded > 0) {
            log.warn("押金悬挂已回收 orderNo={} 退还点数={}", order.getOrderNo(), refunded);
        }
        return false;
    }
}
