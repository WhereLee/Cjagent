package com.wherelee.cabinet.device.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wherelee.cabinet.device.api.ReportEvent;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("biz_device_report")
public class BizDeviceReport {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;

    private Long cabinetId;

    private Long slotId;

    private Long orderId;

    private Long seq;

    private ReportEvent eventType;

    private String dedupKey;

    private String payload;

    private LocalDateTime reportedAt;

    private LocalDateTime receivedAt;

    private LocalDateTime createTime;
}
