package com.wherelee.cabinet.funds.domain;

import com.wherelee.cabinet.domain.entity.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("biz_point_account")
public class BizPointAccount extends BaseEntity {

    private Long customerId;

    private Long points;

    private Long frozenPoints;

    private Long depositPoints;

    @Version
    private Integer version;
}
