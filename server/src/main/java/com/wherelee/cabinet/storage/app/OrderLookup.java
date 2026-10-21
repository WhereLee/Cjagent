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

/**
 * 订单的取单与编号读取：下单、取件、开门、重置凭据几处共用同一套口径。
 *
 * <p>为什么单独抽出来：{@code findOwned} 的“不区分不存在与不是你的”是全项目统一的防枚举口径，
 * 分散在多个用例里时，任何一处改成“区分 403/404”都会成为单号探测的入口。
 */
@Component
public class OrderLookup {

    private final BizStorageOrderMapper orderMapper;
    private final SlotQueryPort slotQueries;

    public OrderLookup(BizStorageOrderMapper orderMapper, SlotQueryPort slotQueries) {
        this.orderMapper = orderMapper;
        this.slotQueries = slotQueries;
    }

    /** 取本人订单；不区分“不存在”与“不是你的”，避免用 404/403 差值枚举他人单号。 */
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
