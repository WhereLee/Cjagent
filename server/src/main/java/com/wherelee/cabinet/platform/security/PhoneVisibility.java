package com.wherelee.cabinet.platform.security;

import com.wherelee.cabinet.common.mask.MaskType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class PhoneVisibility {

    public static final String PERMISSION = "customer:phone:view";

    private PhoneVisibility() {
    }

    public static boolean allowed() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        return authentication.getAuthorities().stream()
                .anyMatch(a -> PERMISSION.equals(a.getAuthority()));
    }

    public static String visible(String phone) {
        if (phone == null || phone.isBlank() || allowed()) {
            return phone;
        }
        return MaskType.PHONE.mask(phone);
    }
}
