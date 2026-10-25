package com.wherelee.cabinet.identity.app;
import com.wherelee.cabinet.platform.tenant.TenantGuard;

import com.wherelee.cabinet.identity.api.CustomerLoginRequest;
import com.wherelee.cabinet.identity.api.CustomerLoginView;
import com.wherelee.cabinet.identity.api.CustomerView;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.common.security.AuthConstants;
import com.wherelee.cabinet.app.CabinetProperties;
import com.wherelee.cabinet.identity.domain.BizCustomer;
import com.wherelee.cabinet.domain.entity.SysTenant;
import com.wherelee.cabinet.identity.infra.BizCustomerMapper;
import com.wherelee.cabinet.platform.security.TokenIssuer;
import com.wherelee.cabinet.platform.security.TokenPair;
import com.wherelee.cabinet.platform.security.Caller;
import com.wherelee.cabinet.infrastructure.wechat.MiniAppClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Service
public class CustomerAuthService implements com.wherelee.cabinet.identity.api.CustomerAuthPort {

    private static final Logger log = LoggerFactory.getLogger(CustomerAuthService.class);

    private final MiniAppClient miniAppClient;
    private final BizCustomerMapper customerMapper;
    private final TenantGuard tenantGuard;
    private final TokenIssuer tokenIssuer;
    private final CabinetProperties properties;

    public CustomerAuthService(MiniAppClient miniAppClient,
                               BizCustomerMapper customerMapper,
                               TenantGuard tenantGuard,
                               TokenIssuer tokenIssuer,
                               CabinetProperties properties) {
        this.miniAppClient = miniAppClient;
        this.customerMapper = customerMapper;
        this.tenantGuard = tenantGuard;
        this.tokenIssuer = tokenIssuer;
        this.properties = properties;
    }

    @Override
    public CustomerLoginView login(CustomerLoginRequest request) {
        String openId = miniAppClient.resolveOpenId(request.code());
        BizCustomer customer = customerMapper.selectByOpenId(openId);

        boolean newRegister = customer == null;
        if (newRegister) {
            customer = registerCustomer(openId, request.tenantCode(), request.phone());
        } else {
            assertExistingCustomerUsable(customer, request.tenantCode());
            final Long customerTenant = customer.getTenantId();
            final Long customerId = customer.getId();
            TenantContext.runAs(customerTenant, () -> customerMapper.touchLastLogin(customerId));
        }

        TokenPair pair = tokenIssuer.issueAndRemember(AuthConstants.END_MINI,
                customer.getId(), customer.getTenantId(), displayName(customer));
        log.info("客户登录成功 tenantId={} customerId={} 是否新注册={} mock={}",
                customer.getTenantId(), customer.getId(), newRegister, properties.getMini().isMockLogin());
        return new CustomerLoginView(pair.accessToken(), pair.refreshToken(), pair.tokenType(),
                pair.accessExpiresIn(), pair.refreshExpiresIn(), customer.getId(), customer.getTenantId(),
                newRegister);
    }

    private BizCustomer registerCustomer(String openId, String tenantCode, String phone) {
        if (!StringUtils.hasText(tenantCode)) {
            throw new BizException(ResultCode.TENANT_INVALID, "首次登录需要指定归属运营商（扫码或选择服务商）");
        }
        SysTenant tenant = tenantGuard.requireUsableByCode(tenantCode);
        String trimmed = phone == null ? null : phone.trim();
        if (trimmed == null || !trimmed.matches("1[3-9]\\d{9}")) {
            throw new BizException(ResultCode.PARAM_INVALID, "首次注册需绑定手机号（十一位）");
        }

        BizCustomer customer = new BizCustomer();
        customer.setOpenId(openId);
        customer.setPhone(trimmed);
        customer.setNickname("客户" + openId.substring(Math.max(0, openId.length() - 4)));
        customer.setStatus(1);
        customer.setRegisterTime(LocalDateTime.now());
        customer.setLastLoginAt(LocalDateTime.now());
        TenantContext.runAs(tenant.getId(), () -> customerMapper.insert(customer));
        return customer;
    }

    private void assertExistingCustomerUsable(BizCustomer customer, String requestedTenantCode) {
        if (!Integer.valueOf(1).equals(customer.getStatus())) {
            throw new BizException(ResultCode.FORBIDDEN, "账号已被冻结，请联系客服");
        }
        if (StringUtils.hasText(requestedTenantCode)) {
            SysTenant requested = tenantGuard.requireUsableByCode(requestedTenantCode);
            if (!requested.getId().equals(customer.getTenantId())) {
                log.warn("客户租户不符 customerId={} 归属={} 请求={}", customer.getId(), customer.getTenantId(),
                        requested.getId());
                throw new BizException(ResultCode.TENANT_INVALID, "该账号不属于此运营商");
            }
        } else {
            tenantGuard.requireUsableById(customer.getTenantId());
        }
    }

    @Override
    public TokenPair refresh(String refreshToken) {
        return tokenIssuer.refresh(AuthConstants.END_MINI, refreshToken);
    }

    @Override
    public void logout(Caller currentAccess, String refreshToken) {
        tokenIssuer.logout(AuthConstants.END_MINI, currentAccess, refreshToken);
    }

    @Override
    public CustomerView currentProfile(Caller current) {
        BizCustomer customer = customerMapper.selectById(current.subjectId());
        if (customer == null) {
            throw new BizException(ResultCode.UNAUTHORIZED, "账号不存在或已被删除，请重新登录");
        }
        return new CustomerView(customer.getId(), customer.getTenantId(), customer.getNickname(),
                customer.getPhone(), customer.getStatus(), customer.getRegisterTime(), customer.getLastLoginAt());
    }

    private String displayName(BizCustomer customer) {
        return StringUtils.hasText(customer.getNickname()) ? customer.getNickname() : "cust-" + customer.getId();
    }
}
