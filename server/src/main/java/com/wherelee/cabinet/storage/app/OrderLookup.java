package com.wherelee.cabinet.storage.app;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import com.wherelee.cabinet.locker.api.CabinetSnapshot;
import com.wherelee.cabinet.locker.api.SlotQueryPort;
import com.wherelee.cabinet.locker.api.SlotSnapshot;
import org.springframework.stereotype.Component;

@Component
public class OrderLookup {

    private final BizStorageOrderMapper orderMapper;
    private final SlotQueryPort slotQueries;

    public OrderLookup(BizStorageOrderMapper orderMapper, SlotQueryPort slotQueries) {
        this.orderMapper = orderMapper;
        this.slotQueries = slotQueries;
    }

    public BizStorageOrder findOwned(Long customerId, String orderNo) {
        BizStorageOrder order = orderMapper.selectOne(Wrappers.<BizStorageOrder>lambdaQuery()
                .eq(BizStorageOrder::getOrderNo, orderNo));
        if (order == null || !order.getCustomerId().equals(customerId)) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "寄存单不存在");
        }
        return order;
    }

    public String slotNoOf(Long slotId) {
        return slotQueries.slot(slotId).map(SlotSnapshot::slotNo).orElse("-");
    }

    public String cabinetNoOf(Long cabinetId) {
        return slotQueries.cabinet(cabinetId).map(CabinetSnapshot::cabinetNo).orElse("-");
    }
}
