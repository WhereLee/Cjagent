package com.wherelee.cabinet.storage.app;

import com.wherelee.cabinet.storage.api.StorageOrderView;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.identity.api.CustomerQueryPort;
import com.wherelee.cabinet.identity.api.CustomerSnapshot;
import com.wherelee.cabinet.domain.entity.BizStorageOrder;
import com.wherelee.cabinet.infrastructure.mapper.BizStorageOrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PublicPickupService implements com.wherelee.cabinet.storage.api.PublicPickupPort {

    private static final Logger log = LoggerFactory.getLogger(PublicPickupService.class);

    private static final String GENERIC_FAILURE = "取件码或手机尾号不正确，请核对后重试";

    private final BizStorageOrderMapper orderMapper;
    private final CustomerQueryPort customers;
    private final StorageOrderService orders;

    public PublicPickupService(BizStorageOrderMapper orderMapper, CustomerQueryPort customers,
                               StorageOrderService orders) {
        this.orderMapper = orderMapper;
        this.customers = customers;
        this.orders = orders;
    }

    public StorageOrderView pickup(String voucherCode, String phoneTail) {
        if (voucherCode == null || voucherCode.isBlank()) {
            throw new BizException(ResultCode.PARAM_INVALID, "请填写取件码");
        }
        if (phoneTail == null || !phoneTail.matches("\\d{4}")) {
            throw new BizException(ResultCode.PARAM_INVALID, "请填写手机号后四位");
        }
        String code = voucherCode.trim();

        BizStorageOrder hit = orderMapper.pickAliveByVoucher(code);
        if (hit == null) {
            log.warn("双因子取件未命中任何在途单（码错、或这单已经结束/取消）");
            throw new BizException(ResultCode.BIZ_ERROR, GENERIC_FAILURE);
        }

        return TenantContext.callAs(hit.getTenantId(), () -> {
            CustomerSnapshot customer = customers.byId(hit.getCustomerId()).orElse(null);
            if (!tailMatches(customer, phoneTail)) {
                orders.registerVoucherWrong(hit);
                throw new BizException(ResultCode.BIZ_ERROR, GENERIC_FAILURE);
            }
            return orders.pickup(hit.getCustomerId(), hit.getOrderNo(), code);
        });
    }

    private boolean tailMatches(CustomerSnapshot customer, String phoneTail) {
        String phone = customer == null ? null : customer.phone();
        return phone != null && phone.length() >= 4
                && phone.substring(phone.length() - 4).equals(phoneTail);
    }
}
