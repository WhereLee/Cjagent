package com.wherelee.cabinet.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 通知与人工待办（19D）。两种形态同表：给客户看的站内消息、给业务员办的"必须打电话"任务。
 *
 * <p>{@code dedupKey} 是唯一索引列，也是这条记录"为什么只会出现一次"的答案：
 * 提醒阶梯每轮巡检都会重进升级分支，没有它就等于把队列刷满重复待办。
 */
@Getter
@Setter
@TableName("biz_notice")
public class BizNotice extends BaseEntity {

    private Long customerId;
    private Long workOrderId;
    private String orderNo;

    /** SYSTEM_MESSAGE / PHONE_CALL。 */
    private String channel;

    private String kind;
    private String dedupKey;
    private String content;

    /** 站内消息=已读时刻；PHONE_CALL=回填拨打结果的时刻。为空表示还欠着。 */
    private LocalDateTime doneAt;
    private String doneNote;
}
