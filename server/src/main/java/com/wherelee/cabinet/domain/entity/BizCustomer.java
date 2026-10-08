package com.wherelee.cabinet.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 寄存客户（用户端登录主体）。
 *
 * <p>前身是换电域的 {@code sys_rider}：列几乎一一对应（openid、昵称、手机号、状态、注册与登录时间），
 * 领域切换后用 {@code RENAME TABLE} 转成本表（见 V5 迁移的说明）。之所以费劲改名而不是新表迁移：
 * 业务表（寄存单）的 {@code customer_id} 指向它，留着 rider 语义等于把换电名词钉进储物柜的数据模型，
 * 以后再改就要动接口契约与历史数据。
 *
 * <p>客户归属某个租户（运营商），订单、点数账户、押金都挂在 {@code tenantId} 这条链上；
 * {@code tenantId} 不能为空，否则客户会变成"谁都看不到"的黑洞账号。
 */
@Getter
@Setter
@TableName("biz_customer")
public class BizCustomer extends BaseEntity {

    /** 微信小程序 openid，全局唯一（DDL 是 uk(open_id)）。登录身份的唯一来源。 */
    private String openId;

    private String unionId;

    /** PII，返回前端需脱敏（@JsonMask）。 */
    private String phone;

    private String nickname;

    /** 1 正常 0 冻结。冻结后权限装载返回空集合 → 403。 */
    private Integer status;

    private LocalDateTime registerTime;

    private LocalDateTime lastLoginAt;
}
