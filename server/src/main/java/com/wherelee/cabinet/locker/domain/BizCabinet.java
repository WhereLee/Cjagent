package com.wherelee.cabinet.locker.domain;
import com.wherelee.cabinet.domain.entity.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wherelee.cabinet.locker.api.CabinetStatus;
import com.wherelee.cabinet.locker.api.OnlineState;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("biz_cabinet")
public class BizCabinet extends BaseEntity {

    private Long siteId;
    private String cabinetNo;
    private String name;
    private Long modelId;
    private CabinetStatus cabinetStatus;
    private OnlineState onlineState;
    private LocalDateTime lastHeartbeatAt;
}
