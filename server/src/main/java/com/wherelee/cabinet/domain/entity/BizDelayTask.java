package com.wherelee.cabinet.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wherelee.cabinet.domain.enums.TaskStatus;
import com.wherelee.cabinet.domain.enums.TaskType;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 延迟任务（<b>DB 是唯一真相源</b>，Redis ZSet 与 MQ 定时消息都只是"提醒"）。
 *
 * <p>这是本刀的核心立场：三种触发方式都可以决定"什么时候去看一眼"，
 * 但<b>谁真正执行由这张表上的条件更新决定</b>。否则就会出现"Redis 认为发了、MQ 认为投了、
 * 业务被执行两遍"或者反过来谁都没做——因为跨存储没有事务。
 *
 * <p>租约字段（{@code leaseOwner} / {@code leaseExpireAt}）让"执行中"可被接管：
 * 实例被 kill 时任务不会永久卡在 RUNNING。
 *
 * <p>所有任务都带真实 {@code tenantId}（handler 在该租户上下文里执行），
 * 因此 worker 的抢占语句必须显式跳过租户注入，而业务副作用仍然受隔离——两者方吐相反。
 */
@Getter
@Setter
@TableName("biz_delay_task")
public class BizDelayTask extends BaseEntity {

    private TaskType taskType;

    /** 业务键（多为订单号），与 taskType 组成唯一键，同类任务不重复登记。 */
    private String bizKey;

    private LocalDateTime fireAt;

    private TaskStatus status;

    private Integer attempt;

    /** 失败原因要能被清空（重试成功后不留旧错误），所以 ALWAYS。 */
    @TableField(value = "last_error", updateStrategy = FieldStrategy.ALWAYS)
    private String lastError;

    private String leaseOwner;

    private LocalDateTime leaseExpireAt;
}
