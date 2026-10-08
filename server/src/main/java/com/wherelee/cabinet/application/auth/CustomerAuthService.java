package com.wherelee.cabinet.application.auth;

import com.wherelee.cabinet.application.auth.dto.CustomerLoginRequest;
import com.wherelee.cabinet.application.auth.dto.CustomerLoginView;
import com.wherelee.cabinet.application.auth.dto.CustomerView;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.common.security.AuthConstants;
import com.wherelee.cabinet.config.CabinetProperties;
import com.wherelee.cabinet.domain.entity.BizCustomer;
import com.wherelee.cabinet.domain.entity.SysTenant;
import com.wherelee.cabinet.infrastructure.mapper.BizCustomerMapper;
import com.wherelee.cabinet.infrastructure.security.TokenIssuer;
import com.wherelee.cabinet.infrastructure.security.TokenPair;
import com.wherelee.cabinet.infrastructure.security.VerifiedToken;
import com.wherelee.cabinet.infrastructure.wechat.MiniAppClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 用户端登录编排：code 换 openid → 定位/注册客户 → 签发凭证。
 *
 * <p><b>租户归属怎么定（当前实现的口径）</b>：openid 全局唯一，登录时先按 openid 反查客户
 * （这一步必须跳过租户过滤，见 {@code BizCustomerMapper#selectByOpenId}）；
 * 老账号直接沿用它自己的 {@code tenant_id}，请求里若又传了别的租户编码会被拒绝；
 * 新客户首次登录必须带 {@code tenantCode} 才能落租户。
 *
 * <p>这是对真实业务的<b>有意简化</b>（已登记）：储物柜场景下归属应由<b>扫柜机码带过来的
 * 站点/运营商</b>决定，而不是前端传值。接口形态（code + 上下文 → token）保持不变，
 * 第 15 刀做用户端扫码流程时替换归属解析这一环。
 */
@Service
public class CustomerAuthService {

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

    public CustomerLoginView login(CustomerLoginRequest request) {
        String openId = miniAppClient.resolveOpenId(request.code());
        BizCustomer customer = customerMapper.selectByOpenId(openId);

        boolean newRegister = customer == null;
        if (newRegister) {
            customer = registerCustomer(openId, request.tenantCode());
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

    private BizCustomer registerCustomer(String openId, String tenantCode) {
        if (!StringUtils.hasText(tenantCode)) {
            throw new BizException(ResultCode.TENANT_INVALID, "首次登录需要指定归属运营商（扫码或选择服务商）");
        }
        SysTenant tenant = tenantGuard.requireUsableByCode(tenantCode);

        BizCustomer customer = new BizCustomer();
        customer.setOpenId(openId);
        customer.setNickname("客户" + openId.substring(Math.max(0, openId.length() - 4)));
        customer.setStatus(1);
        customer.setRegisterTime(LocalDateTime.now());
        // 注册本次就是首次登录；不写的话新客户进"我的"会看到"最后登录 -"这种语义空缺
        customer.setLastLoginAt(LocalDateTime.now());
        // 写入必须在该租户上下文里做：biz_customer 不是白名单表，租户守卫会拒绝无上下文的写入
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
                // 老账号的归属不容更改：否则"A 运营商的客户"能通过传 B 的编码切到 B 的池子里
                log.warn("客户租户不符 customerId={} 归属={} 请求={}", customer.getId(), customer.getTenantId(),
                        requested.getId());
                throw new BizException(ResultCode.TENANT_INVALID, "该账号不属于此运营商");
            }
        } else {
            // 没带编码也要确认原租户仍然可用（停用/到期后客户不该还能进）
            tenantGuard.requireUsableById(customer.getTenantId());
        }
    }

    public TokenPair refresh(String refreshToken) {
        return tokenIssuer.refresh(AuthConstants.END_MINI, refreshToken);
    }

    public void logout(VerifiedToken currentAccess, String refreshToken) {
        tokenIssuer.logout(AuthConstants.END_MINI, currentAccess, refreshToken);
    }

    /**
     * 当前客户信息。租户上下文已由过滤器按凭证建立，所以 selectById 自带租户条件。
     *
     * <p>回的是 {@link CustomerView} 而不是实体：openId / unionId 属于服务端侧身份标识，
     * 下发给客户端没有任何用处，却把一个稳定主键泄露到了前端与日志里。
     */
    public CustomerView currentProfile(VerifiedToken current) {
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
