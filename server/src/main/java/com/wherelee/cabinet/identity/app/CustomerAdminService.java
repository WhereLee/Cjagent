package com.wherelee.cabinet.identity.app;

import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.identity.domain.BizCustomer;
import com.wherelee.cabinet.identity.infra.BizCustomerMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 客户账号的后台处置（禁用 / 启用）。
 *
 * <p>为什么不在柜机控制台里：它改的是<b>身份</b>的状态位，与柜机、格口、订单都没有关系；
 * 放在柜机服务里会让人以为“禁用客户”是柜机的事，下次就会有人从这里加格子相关的判断。
 *
 * <p>它只改一个状态位：<b>拦下登录与下单的是底座已有的机制</b>（status=0 时权限装载为空集合
 * → 403），不在这里再造一份检查。这也是为什么这里不需要任何“禁用阈值”。
 *
 * <p>与欠费拦截分工不同，不能合并：欠费拦截是<b>系统自动、补缴即自愈</b>；
 * 禁用是<b>人工判定、人工解除</b>，针对的是拒付与反复恶意行为。取件两边都不拦。
 */
@Service
public class CustomerAdminService {

    private static final Logger log = LoggerFactory.getLogger(CustomerAdminService.class);

    private final BizCustomerMapper customerMapper;

    public CustomerAdminService(BizCustomerMapper customerMapper) {
        this.customerMapper = customerMapper;
    }

    @Transactional
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
