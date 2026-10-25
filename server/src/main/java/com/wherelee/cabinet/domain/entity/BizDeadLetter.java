package com.wherelee.cabinet.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("biz_dead_letter")
public class BizDeadLetter extends BaseEntity {

    private String topic;

    private String tag;

    private String dlqTopic;

    private String msgKey;

    private String payload;

    private String error;

    private Integer reconsumeTimes;

    private String status;

    private LocalDateTime receivedAt;

    private LocalDateTime replayedAt;

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_REPLAYED = "REPLAYED";
}
