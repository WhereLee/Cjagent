package com.wherelee.cabinet.application.user.dto;

import com.wherelee.cabinet.common.mask.JsonMask;
import com.wherelee.cabinet.common.mask.MaskType;

import java.time.LocalDateTime;

/**
 * 后台账号的列表视图。
 *
 * <p>放在 application 层而不是 Controller 里：Service 要返回它，若它定义在 interfaces 包，
 * 就变成"下层依赖上层"，ArchUnit 会直接报错。视图模型属于用例的输出契约。
 *
 * <p>{@code phone} 标了 {@link JsonMask}：这是脱敏注解的第一个真实使用者。
 * 手机号在本系统里既是联系手段又是准标识符（能定位到人），列表页没有理由返回全文。
 *
 * <p>这里<b>不包含 passwordHash</b>，也不指望有人把它塞进来：视图与实体分离本身就是
 * 一道防线，实体直出早晚会出现"字段多了个哈希"的事故。
 */
public record UserView(Long id,
                       String username,
                       String realName,
                       @JsonMask(MaskType.PHONE) String phone,
                       Integer status,
                       LocalDateTime lastLoginAt,
                       LocalDateTime createTime) {
}
