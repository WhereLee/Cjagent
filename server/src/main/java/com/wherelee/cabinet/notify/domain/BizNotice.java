package com.wherelee.cabinet.notify.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.wherelee.cabinet.domain.entity.BaseEntity;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("biz_notice")
public class BizNotice extends BaseEntity {

    private Long customerId;
    private Long workOrderId;
    private String orderNo;

    private String channel;

    private String kind;
    private String dedupKey;
    private String content;

    private LocalDateTime doneAt;
    private String doneNote;
}
