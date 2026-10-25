package com.wherelee.cabinet.identity.domain;
import com.wherelee.cabinet.domain.entity.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("biz_customer")
public class BizCustomer extends BaseEntity {

    private String openId;

    private String unionId;

    private String phone;

    private String nickname;

    private Integer status;

    private LocalDateTime registerTime;

    private LocalDateTime lastLoginAt;
}
