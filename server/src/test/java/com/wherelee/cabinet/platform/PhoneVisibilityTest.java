package com.wherelee.cabinet.platform;

import com.wherelee.cabinet.common.mask.MaskType;
import com.wherelee.cabinet.platform.security.PhoneVisibility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 手机号按权限给明文还是脱敏。
 *
 * <p>这条规则的实质是"默认拒绝"：没有权限点、没有认证、非 Web 线程，一律脱敏。
 * 期望值不写死成 `138****8000`，而是直接调 {@code MaskType.PHONE.mask()}——
 * 这样遮蔽样式改了这里跟着变，测的是"有没有走脱敏"而不是"星号打了几个"。
 */
class PhoneVisibilityTest {

    private static final String PHONE = "13800138000";

    @BeforeEach
    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(String... authorities) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "tester", "n/a",
                Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList()));
    }

    @Test
    @DisplayName("有权限点：原样返回明文")
    void plainWhenAuthorized() {
        authenticate(PhoneVisibility.PERMISSION);

        assertTrue(PhoneVisibility.allowed());
        assertEquals(PHONE, PhoneVisibility.visible(PHONE));
    }

    @Test
    @DisplayName("没有认证：脱敏（定时任务与匿名访问都走这里）")
    void maskedWhenNoAuthentication() {
        assertFalse(PhoneVisibility.allowed());
        assertEquals(MaskType.PHONE.mask(PHONE), PhoneVisibility.visible(PHONE));
    }

    @Test
    @DisplayName("登录了但没这个权限点：照样脱敏")
    void maskedWhenAuthorizedForSomethingElse() {
        authenticate("workorder:online:list", "workorder:field:list");

        assertFalse(PhoneVisibility.allowed());
        assertEquals(MaskType.PHONE.mask(PHONE), PhoneVisibility.visible(PHONE));
    }

    @Test
    @DisplayName("null 与空串原样返回：脱敏一个不存在的号码会把“没有”伪装成“有”")
    void keepsEmpty() {
        authenticate();

        assertNull(PhoneVisibility.visible(null));
        assertEquals("", PhoneVisibility.visible(""));
        assertEquals("   ", PhoneVisibility.visible("   "));
    }
}
