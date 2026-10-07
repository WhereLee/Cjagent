package com.wherelee.cabinet.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 后台用户（运营/商户侧账号）。
 *
 * <p>{@code username} 只在租户内唯一（DDL 是 uk(tenant_id, username)）：不同商户可以有同名账号，
 * 因此登录必须带上租户编码，光靠用户名定位不到唯一的人。
 */
@Getter
@Setter
@TableName("sys_user")
public class SysUser extends BaseEntity {

    private String username;

    /** BCrypt 摘要。绝不明文，也绝不进日志。 */
    private String passwordHash;

    private String realName;

    /** PII，返回前端需脱敏（第四刀的 @JsonMask）。 */
    private String phone;

    /** 1 启用 0 停用。停用账号即使 token 未过期也应被拒绝。 */
    private Integer status;

    private LocalDateTime lastLoginAt;
}
