package com.wherelee.cabinet.device.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.wherelee.cabinet.device.api.CommandAction;
import com.wherelee.cabinet.device.api.CommandState;
import com.wherelee.cabinet.domain.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("biz_device_command")
public class BizDeviceCommand extends BaseEntity {

    private Long orderId;

    private Long cabinetId;

    private Long slotId;

    private String requestId;

    private CommandAction action;

    private CommandState status;

    private Integer retryCount;

    private Long operatorId;

    private LocalDateTime sentAt;

    private LocalDateTime ackedAt;

    @TableField(value = "last_error", updateStrategy = FieldStrategy.ALWAYS)
    private String lastError;

    @Version
    private Integer version;

    public void transitTo(CommandState target) {
        if (status == null) {
            throw new IllegalStateException("指令状态为空，数据异常: " + requestId);
        }
        if (!status.canTransitTo(target)) {
            throw new IllegalStateException("指令 " + requestId + " 不允许从 " + status + " 迁移到 " + target);
        }
        this.status = target;
    }

    public void initStatus() {
        this.status = CommandState.PENDING;
        this.retryCount = 0;
        this.version = 0;
    }
}
