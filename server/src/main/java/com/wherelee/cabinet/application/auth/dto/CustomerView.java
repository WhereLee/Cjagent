package com.wherelee.cabinet.application.auth.dto;

import com.wherelee.cabinet.common.mask.JsonMask;
import com.wherelee.cabinet.common.mask.MaskType;

import java.time.LocalDateTime;

/**
 * 用户端的"我是谁"视图。
 *
 * <p><b>刻意不包含 openId / unionId。</b>它们是服务端侧的身份标识，不是给客户端看的东西：
 * 一旦下发，就等于把一个稳定、可跨接口使用的用户主键暴露到前端与日志里，
 * 而客户端拿到它也没有任何用处（登录凭证已经表明身份）。
 *
 * <p>同理不含 {@code deleted} 等持久化字段——实体直出早晚会被前端"顺手用起来"，
 * 到时就再也改不动了。ArchitectureTest 有一条规则禁止 interfaces 层依赖 domain.entity。
 */
public record CustomerView(Long customerId,
                           Long tenantId,
                           String nickname,
                           @JsonMask(MaskType.PHONE) String phone,
                           Integer status,
                           LocalDateTime registerTime,
                           LocalDateTime lastLoginAt) {
}
