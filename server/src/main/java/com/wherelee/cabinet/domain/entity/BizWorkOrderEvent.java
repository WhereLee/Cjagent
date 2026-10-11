package com.wherelee.cabinet.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 工单流转留痕（19B）。<b>只 INSERT，永不修改</b>——它没有 update_time/deleted/version，
 * 一张可被改写的流水不构成证据。
 *
 * <p>{@code operatorId} 为空表示系统事件（自动转化、自动开单）：<b>“谁做的”与“做了什么”
 * 必须分开记</b>，否则事后会把机器动作读成人的承诺。
 */
@Getter
@Setter
@TableName("biz_work_order_event")
public class BizWorkOrderEvent {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long tenantId;
    private Long workOrderId;

    /** CREATED / CLAIMED / NOTED / DISPATCHED / RESOLVED / AUTO_CLOSED。 */
    private String eventType;

    private Long operatorId;
    private String detail;
    private LocalDateTime createTime;
}
