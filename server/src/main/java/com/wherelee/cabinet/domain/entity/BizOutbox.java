package com.wherelee.cabinet.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wherelee.cabinet.domain.enums.OutboxEventType;
import com.wherelee.cabinet.domain.enums.OutboxStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 待投递的事实（transactional outbox）。
 *
 * <p>它和 {@link BizDelayTask} 的区别就是仓库里为什么要多一张表：
 * 那个表的行是"<b>到点要做的事</b>"（有 fireAt、每类任务各有重试预算、周期型任务还会续排）；
 * 这个表的行是"<b>已经发生、待投递的事实</b>"（没有执行时间概念，只有"投出去没有"）。
 * 两类语义混一张表，{@code fire_at} 对事实没意义、载荷列对延迟任务没意义，以后加字段互相干扰。
 *
 * <p>行由业务在<b>自己的事务里</b>插入（见 {@code OutboxPort}），由中继异步投递。
 * 消费者本来就幂等（工单靠唯一索引、消息靠 dedup_key），所以重复投递无害——
 * 这也是它不需要"执行中"状态、不需要租约的原因。
 */
@Getter
@Setter
@TableName("biz_outbox")
public class BizOutbox extends BaseEntity {

    private OutboxEventType eventType;

    /** 同一事实的去重键，与 eventType 组成唯一键：重复登记不会堆出多条待投递。 */
    private String dedupKey;

    /** 投递所需的事实快照（JSON）。存快照而不是存引用：事实之后可能被改写，投递要按当时的形状走。 */
    private String payload;

    private OutboxStatus status;

    private Integer attempt;

    /** 下次可投时间：失败后退避推远，也是并发实例之间的互斥手段。 */
    private LocalDateTime nextAttemptAt;

    /** 失败原因要能被清空（投递成功后不留旧错误），所以 ALWAYS。 */
    @TableField(value = "last_error", updateStrategy = FieldStrategy.ALWAYS)
    private String lastError;

    private LocalDateTime doneAt;
}
