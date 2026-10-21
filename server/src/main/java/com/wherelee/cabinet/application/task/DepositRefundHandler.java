package com.wherelee.cabinet.application.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.funds.app.PricingPolicy;
import com.wherelee.cabinet.storage.api.FundPort;
import com.wherelee.cabinet.device.app.DeviceCommandService;
import com.wherelee.cabinet.funds.app.ReconcileReport;
import com.wherelee.cabinet.funds.app.ReconcileService;
import com.wherelee.cabinet.locker.api.SlotPort;
import com.wherelee.cabinet.locker.app.SlotCandidateQuery;
import com.wherelee.cabinet.storage.app.StorageOrderService;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.locker.domain.BizCabinet;
import com.wherelee.cabinet.locker.domain.BizCompartment;
import com.wherelee.cabinet.domain.entity.BizDelayTask;
import com.wherelee.cabinet.funds.domain.BizDeposit;
import com.wherelee.cabinet.device.domain.BizDeviceCommand;
import com.wherelee.cabinet.device.domain.BizFaultEvent;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.funds.api.DepositStatus;
import com.wherelee.cabinet.device.api.FaultType;
import com.wherelee.cabinet.domain.enums.OrderStatus;
import com.wherelee.cabinet.locker.api.SlotStatus;
import com.wherelee.cabinet.locker.api.SizeType;
import com.wherelee.cabinet.domain.enums.TaskType;
import com.wherelee.cabinet.locker.infra.SlotPreDeductionService;
import com.wherelee.cabinet.locker.infra.BizCabinetMapper;
import com.wherelee.cabinet.locker.infra.BizCompartmentMapper;
import com.wherelee.cabinet.funds.infra.BizDepositMapper;
import com.wherelee.cabinet.device.infra.BizDeviceCommandMapper;
import com.wherelee.cabinet.device.infra.BizFaultEventMapper;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.List;

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

/** 空闲集合校准：把预扣造成的"少卖漂移"按 DB 真相拉回。 */
