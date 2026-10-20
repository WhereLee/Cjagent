package com.wherelee.cabinet.storage.infra;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import com.wherelee.cabinet.storage.api.OrderQueryPort;
import com.wherelee.cabinet.storage.api.OrderSnapshot;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * {@link OrderQueryPort} 的进程内实现：现在的"远程调用"就是一次 mapper 查询。
 *
 * <p>放在 {@code storage.infra} 而不是 app：它直接碰订单表，属于订单服务自己的数据访问；
 * 将来拆成独立进程时，换掉的只是这个类（改成 HTTP/RPC 客户端），调用方一行不动。
 */
@Component
public class OrderQueryAdapter implements OrderQueryPort {

    private final BizStorageOrderMapper orders;

    public OrderQueryAdapter(BizStorageOrderMapper orders) {
        this.orders = orders;
    }

    @Override
    public Optional<OrderSnapshot> byId(Long orderId) {
        if (orderId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(orders.selectById(orderId)).map(OrderQueryAdapter::toSnapshot);
    }

    @Override
    public List<OrderSnapshot> byIds(Collection<Long> orderIds) {
        if (orderIds == null || orderIds.isEmpty()) {
            return List.of();
        }
        return orders.selectBatchIds(orderIds).stream().map(OrderQueryAdapter::toSnapshot).toList();
    }

    @Override
    public Optional<OrderSnapshot> byOrderNo(String orderNo) {
        if (orderNo == null || orderNo.isBlank()) {
            return Optional.empty();
        }
        // 单号全局唯一（uk_order_no），租户拦截器会自动补 tenant_id
        return Optional.ofNullable(orders.selectOne(Wrappers.<BizStorageOrder>lambdaQuery()
                        .eq(BizStorageOrder::getOrderNo, orderNo)))
                .map(OrderQueryAdapter::toSnapshot);
    }

    private static OrderSnapshot toSnapshot(BizStorageOrder order) {
        return new OrderSnapshot(order.getId(), order.getOrderNo(), order.getCustomerId(),
                order.getCabinetId(), order.getSlotId(),
                order.getStatus() == null ? null : order.getStatus().name(),
                order.getSizeType() == null ? null : order.getSizeType().name());
    }
}
