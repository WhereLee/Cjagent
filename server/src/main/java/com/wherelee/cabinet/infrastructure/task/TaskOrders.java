package com.wherelee.cabinet.infrastructure.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;

public final class TaskOrders {

    private TaskOrders() {
    }

    public static BizStorageOrder find(BizStorageOrderMapper mapper, String orderNo) {
        return mapper.selectOne(Wrappers.<BizStorageOrder>lambdaQuery()
                .eq(BizStorageOrder::getOrderNo, orderNo));
    }
}
