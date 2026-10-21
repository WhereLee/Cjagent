package com.wherelee.cabinet.application.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;

/** 延迟任务处理器共用的取单动作（按单号，不带租户参数——租户由拦截器补齐）。 */
final class TaskOrders {

    private TaskOrders() {
    }

    static BizStorageOrder find(BizStorageOrderMapper mapper, String orderNo) {
        return mapper.selectOne(Wrappers.<BizStorageOrder>lambdaQuery()
                .eq(BizStorageOrder::getOrderNo, orderNo));
    }
}
