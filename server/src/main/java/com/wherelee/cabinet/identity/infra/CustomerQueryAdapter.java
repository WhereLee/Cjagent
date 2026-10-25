package com.wherelee.cabinet.identity.infra;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.wherelee.cabinet.identity.api.CustomerQueryPort;
import com.wherelee.cabinet.identity.api.CustomerSnapshot;
import com.wherelee.cabinet.identity.domain.BizCustomer;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Component
public class CustomerQueryAdapter implements CustomerQueryPort {

    private final BizCustomerMapper customerMapper;

    public CustomerQueryAdapter(BizCustomerMapper customerMapper) {
        this.customerMapper = customerMapper;
    }

    @Override
    public Optional<CustomerSnapshot> byId(Long customerId) {
        if (customerId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(customerMapper.selectById(customerId)).map(CustomerQueryAdapter::toSnapshot);
    }

    @Override
    public List<CustomerSnapshot> byIds(Collection<Long> customerIds) {
        if (customerIds == null || customerIds.isEmpty()) {
            return List.of();
        }
        return customerMapper.selectList(Wrappers.<BizCustomer>lambdaQuery()
                        .in(BizCustomer::getId, customerIds)).stream()
                .map(CustomerQueryAdapter::toSnapshot).toList();
    }

    @Override
    public List<Long> idsByPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return List.of();
        }
        return customerMapper.selectList(Wrappers.<BizCustomer>lambdaQuery()
                        .select(BizCustomer::getId)
                        .eq(BizCustomer::getPhone, phone.trim())).stream()
                .map(BizCustomer::getId).toList();
    }

    private static CustomerSnapshot toSnapshot(BizCustomer customer) {
        return new CustomerSnapshot(customer.getId(), customer.getNickname(), customer.getPhone());
    }
}
