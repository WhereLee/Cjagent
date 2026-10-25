package com.wherelee.cabinet.identity.app;

import com.wherelee.cabinet.identity.domain.BizCustomer;
import com.wherelee.cabinet.identity.infra.BizCustomerMapper;
import com.wherelee.cabinet.platform.security.AuthoritiesResolver;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CustomerAuthoritiesResolver implements AuthoritiesResolver {

    public static final String CUSTOMER_ROLE = "ROLE_CUSTOMER";

    private final BizCustomerMapper customerMapper;

    public CustomerAuthoritiesResolver(BizCustomerMapper customerMapper) {
        this.customerMapper = customerMapper;
    }

    @Override
    public List<String> resolve(Long subjectId) {
        BizCustomer customer = customerMapper.selectById(subjectId);
        if (customer == null || !Integer.valueOf(1).equals(customer.getStatus())) {
            return List.of();
        }
        return List.of(CUSTOMER_ROLE);
    }
}
