package com.wherelee.cabinet.identity.api;

import com.wherelee.cabinet.common.mask.JsonMask;
import com.wherelee.cabinet.common.mask.MaskType;

import java.time.LocalDateTime;

public record UserView(Long id,
                       String username,
                       String realName,
                       @JsonMask(MaskType.PHONE) String phone,
                       Integer status,
                       LocalDateTime lastLoginAt,
                       LocalDateTime createTime) {
}
