package com.wherelee.cabinet.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("sys_user")
public class SysUser extends BaseEntity {

    private String username;

    private String passwordHash;

    private String realName;

    private String phone;

    private Integer status;

    private LocalDateTime lastLoginAt;
}
