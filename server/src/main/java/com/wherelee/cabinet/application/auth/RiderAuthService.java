package com.wherelee.cabinet.application.auth;

import com.wherelee.cabinet.application.auth.dto.RiderLoginRequest;
import com.wherelee.cabinet.application.auth.dto.RiderLoginView;
import com.wherelee.cabinet.application.auth.dto.RiderView;
import com.wherelee.cabinet.common.api.ResultCode;
import com.wherelee.cabinet.common.context.TenantContext;
import com.wherelee.cabinet.common.exception.BizException;
import com.wherelee.cabinet.common.security.AuthConstants;
import com.wherelee.cabinet.config.CabinetProperties;
import com.wherelee.cabinet.domain.entity.SysRider;
import com.wherelee.cabinet.domain.entity.SysTenant;
import com.wherelee.cabinet.infrastructure.mapper.SysRiderMapper;
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
 * 骑手端登录编排：code 换 openid → 定位/注册骑手 → 签发凭证。
 *
 * <p><b>租户归属怎么定（当前实现的口径）</b>：openid 是全局唯一的，登录时先按 openid 反查骑手
 * （这一步必须跳过租户过滤，见 {@code SysRiderMapper#selectByOpenId}）；
 * 老账号直接沿用它自己的 {@code tenant_id}，请求里若又传了别的租户编码会被拒绝。
 * 新骑手首次登录必须带 {@code tenantCode} 才能落租户。
 *
 * <p>这是对真实业务的<b>有意简化</b>，已登记待拍板：企业里租户归属不该由前端传，
 * 而是由「小程序 appId → 租户映射表」或「扫柜机码时带过来的站点归属」决定。
 * 阶段 1 做换电业务时要把这段替换掉，接口形态（code + 上下文 → token）保持不变。
 */
@Service
public class RiderAuthService {

    private static final Logger log = LoggerFactory.getLogger(RiderAuthService.class);

    private final MiniAppClient miniAppClient;
    private final SysRiderMapper riderMapper;
    private final TenantGuard tenantGuard;
    private final TokenIssuer tokenIssuer;
    private final CabinetProperties properties;

    public RiderAuthService(MiniAppClient miniAppClient,
                            SysRiderMapper riderMapper,
                            TenantGuard tenantGuard,
                            TokenIssuer tokenIssuer,
                            CabinetProperties properties) {
        this.miniAppClient = miniAppClient;
        this.riderMapper = riderMapper;
        this.tenantGuard = tenantGuard;
        this.tokenIssuer = tokenIssuer;
        this.properties = properties;
    }

    public RiderLoginView login(RiderLoginRequest request) {
        String openId = miniAppClient.resolveOpenId(request.code());
        SysRider rider = riderMapper.selectByOpenId(openId);

        boolean newRegister = rider == null;
        if (newRegister) {
            rider = registerRider(openId, request.tenantCode());
        } else {
            assertExistingRiderUsable(rider, request.tenantCode());
            final Long riderTenant = rider.getTenantId();
            final Long riderId = rider.getId();
            TenantContext.runAs(riderTenant, () -> riderMapper.touchLastLogin(riderId));
        }

        TokenPair pair = tokenIssuer.issueAndRemember(AuthConstants.END_MINI,
                rider.getId(), rider.getTenantId(), displayName(rider));
        log.info("骑手登录成功 tenantId={} riderId={} 是否新注册={} mock={}",
                rider.getTenantId(), rider.getId(), newRegister, properties.getMini().isMockLogin());
        return new RiderLoginView(pair.accessToken(), pair.refreshToken(), pair.tokenType(),
                pair.accessExpiresIn(), pair.refreshExpiresIn(), rider.getId(), rider.getTenantId(), newRegister);
    }

    private SysRider registerRider(String openId, String tenantCode) {
        if (!StringUtils.hasText(tenantCode)) {
            throw new BizException(ResultCode.TENANT_INVALID, "首次登录需要指定归属租户（扫码或选择服务商）");
        }
        SysTenant tenant = tenantGuard.requireUsableByCode(tenantCode);

        SysRider rider = new SysRider();
        rider.setOpenId(openId);
        rider.setNickname("骑手" + openId.substring(Math.max(0, openId.length() - 4)));
        rider.setStatus(1);
        rider.setRegisterTime(LocalDateTime.now());
        // 写入必须在该租户上下文里做：sys_rider 不是白名单表，租户守卫会拒绝无上下文的写入
        TenantContext.runAs(tenant.getId(), () -> riderMapper.insert(rider));
        return rider;
    }

    private void assertExistingRiderUsable(SysRider rider, String requestedTenantCode) {
        if (!Integer.valueOf(1).equals(rider.getStatus())) {
            throw new BizException(ResultCode.FORBIDDEN, "账号已被冻结，请联系客服");
        }
        if (StringUtils.hasText(requestedTenantCode)) {
            SysTenant requested = tenantGuard.requireUsableByCode(requestedTenantCode);
            if (!requested.getId().equals(rider.getTenantId())) {
                // 老账号的归属不容更改：否则"A 运营商的骑手"能通过传 B 的编码切到 B 的池子里
                log.warn("骑手租户不符 riderId={} 归属={} 请求={}", rider.getId(), rider.getTenantId(),
                        requested.getId());
                throw new BizException(ResultCode.TENANT_INVALID, "该账号不属于此租户");
            }
        } else {
            // 没带编码也要确认原租户仍然可用（停用/到期后骑手不该还能进）
            tenantGuard.requireUsableById(rider.getTenantId());
        }
    }

    public TokenPair refresh(String refreshToken) {
        return tokenIssuer.refresh(AuthConstants.END_MINI, refreshToken);
    }

    public void logout(VerifiedToken currentAccess, String refreshToken) {
        tokenIssuer.logout(AuthConstants.END_MINI, currentAccess, refreshToken);
    }

    /**
     * 当前骑手信息。租户上下文已由过滤器按凭证建立，所以 selectById 自带租户条件。
     *
     * <p>回的是 {@link RiderView} 而不是实体：openId / unionId 属于服务端侧身份标识，
     * 下发给客户端没有任何用处，却把一个稳定主键泄露到了前端与日志里。
     */
    public RiderView currentProfile(VerifiedToken current) {
        SysRider rider = riderMapper.selectById(current.subjectId());
        if (rider == null) {
            throw new BizException(ResultCode.UNAUTHORIZED, "账号不存在或已被删除，请重新登录");
        }
        return new RiderView(rider.getId(), rider.getTenantId(), rider.getNickname(), rider.getPhone(),
                rider.getStatus(), rider.getRegisterTime(), rider.getLastLoginAt());
    }

    private String displayName(SysRider rider) {
        return StringUtils.hasText(rider.getNickname()) ? rider.getNickname() : "rider-" + rider.getId();
    }
}
