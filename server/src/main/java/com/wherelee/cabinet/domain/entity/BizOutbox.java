package com.wherelee.cabinet.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wherelee.cabinet.domain.enums.OutboxEventType;
import com.wherelee.cabinet.domain.enums.OutboxStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("biz_outbox")
public class BizOutbox extends BaseEntity {

    private OutboxEventType eventType;

    private String dedupKey;

    private String payload;

    private OutboxStatus status;

    private Integer attempt;

    private LocalDateTime nextAttemptAt;

    @TableField(value = "last_error", updateStrategy = FieldStrategy.ALWAYS)
    private String lastError;

    private LocalDateTime doneAt;
}
