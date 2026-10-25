package com.wherelee.cabinet.locker.domain;
import com.wherelee.cabinet.domain.entity.BaseEntity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.wherelee.cabinet.locker.api.CompartmentAnomaly;
import com.wherelee.cabinet.locker.api.Presence;
import com.wherelee.cabinet.locker.api.SizeType;
import com.wherelee.cabinet.locker.api.SlotStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.Duration;
import java.time.LocalDateTime;

@Getter
@Setter
@TableName("biz_compartment")
public class BizCompartment extends BaseEntity {

    private Long cabinetId;
    private String slotNo;
    private SizeType sizeType;
    private SlotStatus status;
    private Long currentOrderId;

    private LocalDateTime doorOpenAt;

    private Long doorOpenedBy;

    private LocalDateTime doorClosedAt;

    private Long doorClosedBy;

    private Presence presence;

    private LocalDateTime presenceCheckedAt;

    private CompartmentAnomaly anomaly;

    private LocalDateTime anomalyAt;

    private String anomalyReason;

    @Version
    private Integer version;

    public boolean doorOpen() {
        return doorOpenAt != null;
    }

    public long doorOpenMinutes() {
        return doorOpenAt == null ? 0L : Duration.between(doorOpenAt, LocalDateTime.now()).toMinutes();
    }

    public boolean presenceStillValid(int maxAgeMinutes) {
        return presenceCheckedAt != null
                && Duration.between(presenceCheckedAt, LocalDateTime.now()).toMinutes() <= maxAgeMinutes;
    }

    public boolean confirmedEmpty(int maxAgeMinutes) {
        return presenceStillValid(maxAgeMinutes) && presence != null && presence.confirmsEmpty();
    }

    public boolean assignable() {
        return status != null && status.assignable() && !doorOpen() && anomaly == null;
    }
}
