package com.wherelee.cabinet.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 骑手账号（小程序端登录主体）。
 *
 * <p>骑手归属某个租户（运营商）：换电订单、套餐、余额都挂在 {@code tenantId} 这条链上，
 * 所以 {@code tenantId} 不能为空，否则骑手会变成"谁的都看不到"的黑洞账号。
 */
@Getter
@Setter
@TableName("sys_rider")
public class SysRider extends BaseEntity {

    /** 微信小程序 openid，全局唯一（DDL 是 uk(open_id)）。 */
    private String openId;

    private String unionId;

    /** PII，返回前端需脱敏。 */
    private String phone;

    private String nickname;

    /** 1 正常 0 冻结。 */
    private Integer status;

    private LocalDateTime registerTime;

    private LocalDateTime lastLoginAt;
}
