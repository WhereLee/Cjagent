package com.wherelee.cabinet.identity.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CustomerQueryPort {

    Optional<CustomerSnapshot> byId(Long customerId);

    List<CustomerSnapshot> byIds(Collection<Long> customerIds);

    List<Long> idsByPhone(String phone);
}
