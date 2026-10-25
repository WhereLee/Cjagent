package com.wherelee.cabinet.storage.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface OrderQueryPort {

    Optional<OrderSnapshot> byId(Long orderId);

    List<OrderSnapshot> byIds(Collection<Long> orderIds);

    Optional<OrderSnapshot> byOrderNo(String orderNo);
}
