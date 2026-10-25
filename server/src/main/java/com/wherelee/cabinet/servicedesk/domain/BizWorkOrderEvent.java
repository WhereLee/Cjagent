package com.wherelee.cabinet.servicedesk.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("biz_work_order_event")
public class BizWorkOrderEvent {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;
    private Long workOrderId;

    private String eventType;

    private String actorType;

    private Long operatorId;
    private String traceId;
    private String detail;
    private LocalDateTime createTime;
}
