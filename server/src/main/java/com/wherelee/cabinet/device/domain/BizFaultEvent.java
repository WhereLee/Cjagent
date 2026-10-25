package com.wherelee.cabinet.device.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wherelee.cabinet.device.api.FaultType;
import com.wherelee.cabinet.domain.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@TableName("biz_fault_event")
public class BizFaultEvent extends BaseEntity {

    private Long cabinetId;

    private Long slotId;

    private FaultType faultType;

    private FaultType.Action action;

    private Integer failCount;

    private Long operatorId;

    private String reason;
}
