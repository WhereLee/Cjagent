package com.wherelee.cabinet.servicedesk.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wherelee.cabinet.domain.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("biz_work_order_csat")
public class BizWorkOrderCsat extends BaseEntity {

    private Long workOrderId;
    private Long customerId;
    private Integer score;
    private String comment;
}
