package com.wherelee.cabinet.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wherelee.cabinet.domain.enums.AiJobStatus;
import com.wherelee.cabinet.domain.enums.AiJobType;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("biz_ai_job")
public class BizAiJob extends BaseEntity {

    private AiJobType jobType;

    private String requestKey;

    private Long requesterId;

    private String payload;

    private AiJobStatus status;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer activeFlag;

    private String result;

    @TableField(value = "last_error", updateStrategy = FieldStrategy.ALWAYS)
    private String lastError;

    private Integer attempt;

    private String leaseOwner;

    private LocalDateTime leaseExpireAt;
}
