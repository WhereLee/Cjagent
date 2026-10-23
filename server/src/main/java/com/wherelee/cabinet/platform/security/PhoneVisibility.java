package com.wherelee.cabinet.platform.security;

import com.wherelee.cabinet.common.mask.MaskType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 决定"当前这个请求能不能看明文手机号"，以及不能看时该给什么。
 *
 * <p>为什么不在出参字段上标 {@code @JsonMask}：那个注解是静态的——序列化就脱敏，
 * 表达不了"同一个字段、两种权限"。所以判断放在服务层组装 View 的地方，
 * <b>明文根本不出服务器</b>；把明文发给浏览器再在页面上打码，那不叫保护。
 *
 * <p><b>默认拒绝</b>：拿不到认证、没有权限点、非 Web 线程（定时任务、MQ 消费）一律脱敏。
 * 后两种情况下没有"谁在看"，也就没有谁可以被授权——这与
 * {@link AuthenticatedPrincipal} 不默认成系统用户是同一条理由。
 */
public final class PhoneVisibility {

    /** 权限点编码。必须与 V40 迁移里的 {@code sys_permission.code} 一致。 */
    public static final String PERMISSION = "customer:phone:view";

    private PhoneVisibility() {
    }

    /** 当前请求是否被允许看明文。 */
    public static boolean allowed() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(a -> PERMISSION.equals(a.getAuthority()));
    }

    /**
     * 按权限给出该返回的手机号：有权限原样返回，没权限脱敏。
     *
     * <p>{@code null} 与空串原样返回——脱敏一个不存在的号码没有意义，
     * 而且会把"没有手机号"伪装成一个号码，让调用方以为拿到了数据。
     */
    public static String visible(String phone) {
        if (phone == null || phone.isBlank() || allowed()) {
            return phone;
        }
        return MaskType.PHONE.mask(phone);
    }
}
