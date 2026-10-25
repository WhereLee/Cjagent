package com.wherelee.cabinet.identity.api;

import com.wherelee.cabinet.common.mask.JsonMask;
import com.wherelee.cabinet.common.mask.MaskType;

import java.time.LocalDateTime;

public record CustomerView(Long customerId,
                           Long tenantId,
                           String nickname,
                           @JsonMask(MaskType.PHONE) String phone,
                           Integer status,
                           LocalDateTime registerTime,
                           LocalDateTime lastLoginAt) {
}
