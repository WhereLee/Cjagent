package com.wherelee.cabinet.identity.app;

import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.identity.domain.BizCustomer;
import com.wherelee.cabinet.identity.infra.BizCustomerMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerAdminService implements com.wherelee.cabinet.identity.api.CustomerAdminPort {

    private static final Logger log = LoggerFactory.getLogger(CustomerAdminService.class);

    private final BizCustomerMapper customerMapper;

    public CustomerAdminService(BizCustomerMapper customerMapper) {
        this.customerMapper = customerMapper;
    }

    @Transactional
    @Override
    public void changeCustomerStatus(Long customerId, Integer status, String reason) {
        if (status == null || (status != 0 && status != 1)) {
            throw new BizException(ResultCode.PARAM_INVALID, "状态只能是 1（正常）或 0（禁用）");
        }
        if (reason == null || reason.isBlank()) {
            throw new BizException(ResultCode.PARAM_INVALID, "禁用或启用必须写明事由（它会进审计）");
        }
        BizCustomer customer = customerMapper.selectById(customerId);
        if (customer == null) {
            throw new BizException(ResultCode.RESOURCE_NOT_FOUND, "客户不存在");
        }
        if (java.util.Objects.equals(customer.getStatus(), status)) {
            log.info("客户状态本就是该值，幂等返回 customerId={} status={}", customerId, status);
            return;
        }
        customer.setStatus(status);
        if (customerMapper.updateById(customer) == 0) {
            throw new BizException(ResultCode.SYSTEM_ERROR, "客户信息已被并发修改，请重试 customerId=" + customerId);
        }
        log.warn("客户状态变更 customerId={} status={} 事由={}", customerId, status, reason);
    }
}
