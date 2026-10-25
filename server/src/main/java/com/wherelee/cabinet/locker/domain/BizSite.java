package com.wherelee.cabinet.locker.domain;
import com.wherelee.cabinet.domain.entity.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@TableName("biz_site")
public class BizSite extends BaseEntity {

    private String siteCode;
    private String name;
    private String category;
    private String address;
    private BigDecimal longitude;
    private BigDecimal latitude;
    private Integer status;
}
