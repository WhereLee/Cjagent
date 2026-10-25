package com.wherelee.cabinet.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wherelee.cabinet.domain.enums.TaskStatus;
import com.wherelee.cabinet.domain.enums.TaskType;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("biz_delay_task")
public class BizDelayTask extends BaseEntity {

    private TaskType taskType;

    private String bizKey;

    private LocalDateTime fireAt;

    private TaskStatus status;

    private Integer attempt;

    @TableField(value = "last_error", updateStrategy = FieldStrategy.ALWAYS)
    private String lastError;

    private String leaseOwner;

    private LocalDateTime leaseExpireAt;
}
